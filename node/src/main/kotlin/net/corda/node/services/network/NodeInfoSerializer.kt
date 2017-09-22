package net.corda.node.services.network

import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.SignedData
import net.corda.core.internal.VisibleForTesting
import net.corda.core.internal.div
import net.corda.core.internal.isDirectory
import net.corda.core.internal.isRegularFile
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.KeyManagementService
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.utilities.ByteSequence
import net.corda.core.utilities.loggerFor
import rx.Observable
import rx.Scheduler
import rx.schedulers.Schedulers
import java.io.File
import java.nio.file.*
import java.util.concurrent.TimeUnit

/**
 * Class containing the logic to serialize and de-serialize a [NodeInfo] to disk and reading them back.
 */
class NodeInfoSerializer(private val nodePath: Path, private val scheduler: Scheduler = Schedulers.computation()) {

    @VisibleForTesting
    val nodeInfoDirectory = nodePath / NodeInfoSerializer.NODE_INFO_FOLDER
    private val watchService : WatchService? by lazy { initWatch() }

    companion object {
        /**
         * Path relative to the running node where the serialized NodeInfos are stored.
         * Keep this in sync with the value in Cordform.groovy.
         */
        const val NODE_INFO_FOLDER = "additional-node-infos"

        val logger = loggerFor<NodeInfoSerializer>()
    }

    /**
     * Saves the given [NodeInfo] to a path.
     * The node is 'encoded' as a SignedData<NodeInfo>, signed with the owning key of its first identity.
     * The name of the written file will be "nodeInfo-" followed by the hash of the content. The hash in the filename
     * is used so that one can freely copy these files without fearing to overwrite another one.
     *
     * @param path the path where to write the file, if non-existent it will be created.
     * @param nodeInfo the NodeInfo to serialize.
     * @param keyManager a KeyManagementService used to sign the NodeInfo data.
     */
    fun saveToFile(nodeInfo: NodeInfo, keyManager: KeyManagementService) {
        nodePath.toFile().mkdirs()
        val serializedBytes: SerializedBytes<NodeInfo> = nodeInfo.serialize()
        val regSig = keyManager.sign(serializedBytes.bytes, nodeInfo.legalIdentities.first().owningKey)
        val signedData: SignedData<NodeInfo> = SignedData(serializedBytes, regSig)
        val file: File = (nodePath / ("nodeInfo-" + SecureHash.sha256(serializedBytes.bytes).toString())).toFile()
        file.writeBytes(signedData.serialize().bytes)
    }

    /**
     * Loads all the files contained in a given path and returns the deserialized [NodeInfo]s.
     * Signatures are checked before returning a value.
     *
     * @return a list of [NodeInfo]s
     */
    fun loadFromDirectory(): List<NodeInfo> {
        val result = mutableListOf<NodeInfo>()

        if (!nodeInfoDirectory.isDirectory()) {
            logger.info("$nodeInfoDirectory isn't a Directory, not loading NodeInfo from files")
            return result
        }
        var readFiles = 0
        for (file in nodeInfoDirectory.toFile().walk().maxDepth(1)) if (file.isFile) {
            processFile(file)?.let {
                result.add(it)
                readFiles++
            }
        }
        logger.info("Succesfully read $readFiles NodeInfo files.")
        return result
    }

    /**
     * Starts polling the node info folder, for each new/modified file [callback] will be invoked.
     * There is no guarantee that the callback is invoked only once per file.
     *
     */
    fun pollDirectory() : Observable<NodeInfo> {
        return Observable.interval(5, TimeUnit.SECONDS, scheduler)
                .flatMapIterable { _ -> pollWatch() }
    }

    // Polls the watchService for changes to [nodeInfoDirectory], invoke [callback] for
    // each new/modified entry.
    private fun pollWatch() : List<NodeInfo> {
        val result = ArrayList<NodeInfo>()
        if (watchService == null)
            return result

        val watchKey: WatchKey? = watchService!!.poll()
        // This can happen and it simply means that there are no events.
        if (watchKey == null) return result


        for (event in watchKey.pollEvents()) {
            val kind = event.kind()
            if (kind == StandardWatchEventKinds.OVERFLOW) continue

            @Suppress("UNCHECKED_CAST")  // The actual type of these events is determined by event kinds we register for.
            val ev = event as WatchEvent<Path>
            val filename = ev.context()
            val absolutePath = nodeInfoDirectory.resolve(filename)
            if (absolutePath.isRegularFile()) {
                processFile(absolutePath.toFile())?.let { result.add(it) }
            }
        }
        val valid = watchKey.reset()
        if (!valid) {
            logger.warn("Can't poll $nodeInfoDirectory anymore, it was probably deleted.")
        }
        return result
    }

    private fun processFile(file :File) : NodeInfo? {
        try {
            logger.info("Reading NodeInfo from file: $file")
            val signedData: SignedData<NodeInfo> = ByteSequence.of(file.readBytes()).deserialize()
            val nodeInfo = signedData.verified()
            return nodeInfo
        } catch (e: Exception) {
            logger.warn("Exception parsing NodeInfo from file. $file: $e")
            e.printStackTrace()
            return null
        }
    }

    // Create a WatchService watching for changes in nodeInfoDirectory.
    private fun initWatch() : WatchService? {
        if (!nodeInfoDirectory.isDirectory()) {
            logger.warn("Not watching folder $nodeInfoDirectory it doesn't exist or it's not a directory")
            return null
        }
        val watchService = nodeInfoDirectory.fileSystem.newWatchService()
        nodeInfoDirectory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY)
        logger.info("Watching $nodeInfoDirectory for new files")
        return watchService
    }
}