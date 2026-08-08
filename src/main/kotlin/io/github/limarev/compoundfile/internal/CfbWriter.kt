package io.github.limarev.compoundfile.internal

import io.github.limarev.compoundfile.CfbEntryAlreadyExistsException
import io.github.limarev.compoundfile.CfbSpoolingPolicy
import io.github.limarev.compoundfile.CfbVersion
import io.github.limarev.compoundfile.CorruptCfbException
import io.github.limarev.compoundfile.WritableStorage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.util.IdentityHashMap
import java.util.TreeMap

internal class CfbWriter(
    private val destination: SeekableByteChannel,
    private val version: CfbVersion,
    private val spoolingPolicy: CfbSpoolingPolicy,
) : AutoCloseable {
    private val basePosition = destination.position()
    private val root = StorageNode("Root Entry")
    private val streamSpools = ArrayList<StreamSpool>()
    private var built = false

    fun build(block: WritableStorage.() -> Unit) {
        check(!built) { "CFB writer has already been used" }
        built = true
        val storage = WriterStorage(root)
        try {
            storage.block()
        } finally {
            storage.deactivate()
        }
        writeContainer()
    }

    override fun close() {
        streamSpools.forEach(StreamSpool::close)
    }

    private inner class WriterStorage(private val node: StorageNode) : WritableStorage {
        private var active = true

        override fun createStorage(name: String, block: WritableStorage.() -> Unit) {
            requireActive()
            validateName(name)
            if (node.children.containsKey(name)) throw CfbEntryAlreadyExistsException(name)
            val child = StorageNode(name)
            node.children[name] = child
            val storage = WriterStorage(child)
            try {
                storage.block()
            } finally {
                storage.deactivate()
            }
        }

        override fun createStream(name: String, block: OutputStream.() -> Unit) {
            requireActive()
            validateName(name)
            if (node.children.containsKey(name)) throw CfbEntryAlreadyExistsException(name)
            val spool = createStreamSpool()
            streamSpools += spool
            val child = StreamNode(name, spool)
            node.children[name] = child
            val output = ScopedOutputStream(spool.openOutput())
            output.use { it.block() }
            child.size = spool.size
            if (version == CfbVersion.V3 && child.size > 0xFFFFFFFFL) {
                throw IOException("CFB version 3 streams cannot exceed 4 GiB")
            }
        }

        fun deactivate() {
            active = false
        }

        private fun requireActive() {
            if (!active) throw IOException("CFB write storage scope has ended")
        }
    }

    private fun createStreamSpool(): StreamSpool = when (val policy = spoolingPolicy) {
        CfbSpoolingPolicy.InMemory -> MemoryStreamSpool()
        is CfbSpoolingPolicy.TemporaryFiles -> TemporaryFileStreamSpool(policy.directory)
    }

    private class ScopedOutputStream(private val delegate: OutputStream) : OutputStream() {
        private var active = true

        override fun write(value: Int) {
            requireActive()
            delegate.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            requireActive()
            delegate.write(bytes, offset, length)
        }

        override fun flush() {
            requireActive()
            delegate.flush()
        }

        override fun close() {
            if (!active) return
            active = false
            delegate.close()
        }

        private fun requireActive() {
            if (!active) throw IOException("CFB output stream scope has ended")
        }
    }

    private fun writeContainer() {
        val nodes = ArrayList<Node>()
        flatten(root, nodes)
        val ids = IdentityHashMap<Node, Int>()
        nodes.forEachIndexed { index, node -> ids[node] = index }
        val records = nodes.mapIndexed { index, node -> DirectoryRecord(index, node) }
        buildChildTrees(nodes, records, ids)

        val sectorSize = if (version == CfbVersion.V3) 512 else 4096
        val entriesPerSector = sectorSize / 4
        val regularStreams = nodes.filterIsInstance<StreamNode>().filter { it.size >= MINI_STREAM_CUTOFF }
        val miniStreams = nodes.filterIsInstance<StreamNode>().filter { it.size in 1 until MINI_STREAM_CUTOFF }

        var miniSectorCursor = 0
        for (stream in miniStreams) {
            stream.startSector = miniSectorCursor
            stream.sectorCount = ceilDiv(stream.size, MINI_SECTOR_SIZE)
            miniSectorCursor += stream.sectorCount
        }
        val rootMiniSize = miniSectorCursor.toLong() * MINI_SECTOR_SIZE

        var sectorCursor = 0
        for (stream in regularStreams) {
            stream.startSector = sectorCursor
            stream.sectorCount = ceilDiv(stream.size, sectorSize)
            sectorCursor += stream.sectorCount
        }
        val rootMiniStart = if (rootMiniSize == 0L) ENDOFCHAIN else sectorCursor
        val rootMiniSectors = ceilDiv(rootMiniSize, sectorSize)
        sectorCursor += rootMiniSectors

        val directoryStart = sectorCursor
        val directorySectors = maxOf(1, ceilDiv(nodes.size.toLong() * 128L, sectorSize))
        sectorCursor += directorySectors

        val miniFatStart = if (miniSectorCursor == 0) ENDOFCHAIN else sectorCursor
        val miniFatSectors = ceilDiv(miniSectorCursor.toLong() * 4L, sectorSize)
        sectorCursor += miniFatSectors
        val nonAllocationSectors = sectorCursor

        var fatSectors = 0
        var difatSectors = 0
        while (true) {
            val total = nonAllocationSectors + fatSectors + difatSectors
            val requiredFat = ceilDiv(total.toLong(), entriesPerSector)
            val requiredDifat = if (requiredFat <= 109) 0 else ceilDiv((requiredFat - 109).toLong(), entriesPerSector - 1)
            if (requiredFat == fatSectors && requiredDifat == difatSectors) break
            fatSectors = requiredFat
            difatSectors = requiredDifat
        }

        val difatStart = if (difatSectors == 0) ENDOFCHAIN else nonAllocationSectors
        val fatStart = nonAllocationSectors + difatSectors
        val totalSectors = fatStart + fatSectors
        val fatSectorIds = IntArray(fatSectors) { fatStart + it }
        val fat = IntArray(fatSectors * entriesPerSector) { FREESECT }

        for (stream in regularStreams) markChain(fat, stream.startSector, stream.sectorCount)
        markChain(fat, rootMiniStart, rootMiniSectors)
        markChain(fat, directoryStart, directorySectors)
        markChain(fat, miniFatStart, miniFatSectors)
        repeat(difatSectors) { fat[nonAllocationSectors + it] = DIFSECT }
        for (sector in fatSectorIds) fat[sector] = FATSECT

        val miniFat = IntArray(miniFatSectors * entriesPerSector) { FREESECT }
        for (stream in miniStreams) markChain(miniFat, stream.startSector, stream.sectorCount)

        root.startSector = rootMiniStart
        root.size = rootMiniSize

        destination.truncate(basePosition)
        destination.position(basePosition)
        destination.writeFully(
            createHeader(
                sectorSize = sectorSize,
                directoryStart = directoryStart,
                directorySectors = directorySectors,
                fatSectorIds = fatSectorIds,
                miniFatStart = miniFatStart,
                miniFatSectors = miniFatSectors,
                difatStart = difatStart,
                difatSectors = difatSectors,
            ),
        )

        for (stream in regularStreams) writeStreamPadded(stream, sectorSize)
        writeMiniStream(miniStreams, rootMiniSize, sectorSize)
        writeDirectory(records, sectorSize, directorySectors)
        writeIntSectors(miniFat, miniFatSectors, sectorSize)
        writeDifatSectors(fatSectorIds, difatStart, difatSectors, sectorSize)
        writeIntSectors(fat, fatSectors, sectorSize)

        val expectedEnd = basePosition + (totalSectors.toLong() + 1L) * sectorSize
        if (destination.position() != expectedEnd) {
            throw IOException("Internal CFB layout error: expected end $expectedEnd, got ${destination.position()}")
        }
        destination.truncate(expectedEnd)
    }

    private fun flatten(node: Node, result: MutableList<Node>) {
        result += node
        if (node is StorageNode) node.children.values.forEach { flatten(it, result) }
    }

    private fun buildChildTrees(
        nodes: List<Node>,
        records: List<DirectoryRecord>,
        ids: IdentityHashMap<Node, Int>,
    ) {
        for (node in nodes.filterIsInstance<StorageNode>()) {
            var treeRoot = NOSTREAM
            for (child in node.children.values) {
                val childId = ids[child] ?: error("Missing directory ID")
                treeRoot = insertAndBalance(treeRoot, childId, records)
            }
            records[ids[node] ?: error("Missing storage ID")].child = treeRoot
            if (treeRoot != NOSTREAM) records[treeRoot].red = false
        }
    }

    private fun insertAndBalance(initialRoot: Int, id: Int, records: List<DirectoryRecord>): Int {
        var rootId = initialRoot
        var parent = NOSTREAM
        var current = rootId
        while (current != NOSTREAM) {
            parent = current
            current = if (CFB_NAME_COMPARATOR.compare(records[id].node.name, records[current].node.name) < 0) {
                records[current].left
            } else {
                records[current].right
            }
        }
        records[id].parent = parent
        if (parent == NOSTREAM) rootId = id
        else if (CFB_NAME_COMPARATOR.compare(records[id].node.name, records[parent].node.name) < 0) records[parent].left = id
        else records[parent].right = id
        records[id].red = true

        var nodeId = id
        while (nodeId != rootId && isRed(records[nodeId].parent, records)) {
            val parentId = records[nodeId].parent
            val grandparentId = records[parentId].parent
            if (parentId == records[grandparentId].left) {
                val uncle = records[grandparentId].right
                if (isRed(uncle, records)) {
                    records[parentId].red = false
                    records[uncle].red = false
                    records[grandparentId].red = true
                    nodeId = grandparentId
                } else {
                    if (nodeId == records[parentId].right) {
                        nodeId = parentId
                        rootId = rotateLeft(rootId, nodeId, records)
                    }
                    val newParent = records[nodeId].parent
                    val newGrandparent = records[newParent].parent
                    records[newParent].red = false
                    records[newGrandparent].red = true
                    rootId = rotateRight(rootId, newGrandparent, records)
                }
            } else {
                val uncle = records[grandparentId].left
                if (isRed(uncle, records)) {
                    records[parentId].red = false
                    records[uncle].red = false
                    records[grandparentId].red = true
                    nodeId = grandparentId
                } else {
                    if (nodeId == records[parentId].left) {
                        nodeId = parentId
                        rootId = rotateRight(rootId, nodeId, records)
                    }
                    val newParent = records[nodeId].parent
                    val newGrandparent = records[newParent].parent
                    records[newParent].red = false
                    records[newGrandparent].red = true
                    rootId = rotateLeft(rootId, newGrandparent, records)
                }
            }
        }
        records[rootId].red = false
        return rootId
    }

    private fun rotateLeft(root: Int, pivot: Int, records: List<DirectoryRecord>): Int {
        var rootId = root
        val replacement = records[pivot].right
        records[pivot].right = records[replacement].left
        if (records[replacement].left != NOSTREAM) records[records[replacement].left].parent = pivot
        records[replacement].parent = records[pivot].parent
        if (records[pivot].parent == NOSTREAM) rootId = replacement
        else if (pivot == records[records[pivot].parent].left) records[records[pivot].parent].left = replacement
        else records[records[pivot].parent].right = replacement
        records[replacement].left = pivot
        records[pivot].parent = replacement
        return rootId
    }

    private fun rotateRight(root: Int, pivot: Int, records: List<DirectoryRecord>): Int {
        var rootId = root
        val replacement = records[pivot].left
        records[pivot].left = records[replacement].right
        if (records[replacement].right != NOSTREAM) records[records[replacement].right].parent = pivot
        records[replacement].parent = records[pivot].parent
        if (records[pivot].parent == NOSTREAM) rootId = replacement
        else if (pivot == records[records[pivot].parent].right) records[records[pivot].parent].right = replacement
        else records[records[pivot].parent].left = replacement
        records[replacement].right = pivot
        records[pivot].parent = replacement
        return rootId
    }

    private fun isRed(id: Int, records: List<DirectoryRecord>): Boolean = id != NOSTREAM && records[id].red

    private fun markChain(table: IntArray, start: Int, count: Int) {
        if (count == 0) return
        if (start < 0 || start + count > table.size) throw CorruptCfbException("Internal sector allocation overflow")
        repeat(count) { offset -> table[start + offset] = if (offset == count - 1) ENDOFCHAIN else start + offset + 1 }
    }

    private fun createHeader(
        sectorSize: Int,
        directoryStart: Int,
        directorySectors: Int,
        fatSectorIds: IntArray,
        miniFatStart: Int,
        miniFatSectors: Int,
        difatStart: Int,
        difatSectors: Int,
    ): ByteBuffer {
        val header = littleBuffer(sectorSize)
        header.put(SIGNATURE)
        header.position(24)
        header.putShort(0x003E)
        header.putShort(if (version == CfbVersion.V3) 3 else 4)
        header.putShort(0xFFFE.toShort())
        header.putShort(if (version == CfbVersion.V3) 9 else 12)
        header.putShort(6)
        header.position(40)
        header.putInt(if (version == CfbVersion.V3) 0 else directorySectors)
        header.putInt(fatSectorIds.size)
        header.putInt(directoryStart)
        header.putInt(0)
        header.putInt(MINI_STREAM_CUTOFF.toInt())
        header.putInt(miniFatStart)
        header.putInt(miniFatSectors)
        header.putInt(difatStart)
        header.putInt(difatSectors)
        repeat(109) { index -> header.putInt(fatSectorIds.getOrElse(index) { FREESECT }) }
        header.position(sectorSize)
        header.flip()
        return header
    }

    private fun writeStreamPadded(stream: StreamNode, unitSize: Int) {
        stream.spool.openInput().use { input ->
            val buffer = ByteArray(8192)
            var remaining = stream.size
            while (remaining > 0L) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw IOException("Staged CFB stream ended unexpectedly")
                destination.writeFully(ByteBuffer.wrap(buffer, 0, count))
                remaining -= count
            }
        }
        writeZeros(padding(stream.size, unitSize))
    }

    private fun writeMiniStream(streams: List<StreamNode>, size: Long, sectorSize: Int) {
        for (stream in streams) writeStreamPadded(stream, MINI_SECTOR_SIZE)
        writeZeros(padding(size, sectorSize))
    }

    private fun writeDirectory(records: List<DirectoryRecord>, sectorSize: Int, sectorCount: Int) {
        val output = littleBuffer(sectorCount * sectorSize)
        for (record in records) writeDirectoryEntry(output, record)
        output.position(output.capacity())
        output.flip()
        destination.writeFully(output)
    }

    private fun writeDirectoryEntry(output: ByteBuffer, record: DirectoryRecord) {
        val entry = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        val encodedName = record.node.name.toByteArray(Charsets.UTF_16LE)
        entry.put(encodedName)
        entry.putShort(64, (encodedName.size + 2).toShort())
        entry.put(66, when (record.node) {
            root -> 5
            is StorageNode -> 1
            is StreamNode -> 2
        })
        entry.put(67, if (record.red) 0 else 1)
        entry.putInt(68, record.left)
        entry.putInt(72, record.right)
        entry.putInt(76, record.child)
        entry.putInt(116, record.node.startSector)
        entry.putLong(120, record.node.size)
        output.put(entry.array())
    }

    private fun writeIntSectors(values: IntArray, sectorCount: Int, sectorSize: Int) {
        if (sectorCount == 0) return
        val output = littleBuffer(sectorCount * sectorSize)
        values.forEach(output::putInt)
        output.position(output.capacity())
        output.flip()
        destination.writeFully(output)
    }

    private fun writeDifatSectors(fatSectorIds: IntArray, start: Int, sectorCount: Int, sectorSize: Int) {
        if (sectorCount == 0) return
        val entriesPerSector = sectorSize / 4
        var fatIndex = 109
        repeat(sectorCount) { index ->
            val output = littleBuffer(sectorSize)
            repeat(entriesPerSector - 1) {
                output.putInt(if (fatIndex < fatSectorIds.size) fatSectorIds[fatIndex++] else FREESECT)
            }
            output.putInt(if (index == sectorCount - 1) ENDOFCHAIN else start + index + 1)
            output.flip()
            destination.writeFully(output)
        }
    }

    private fun writeZeros(count: Int) {
        if (count == 0) return
        destination.writeFully(ByteBuffer.allocate(count))
    }

    private fun padding(size: Long, unitSize: Int): Int {
        val remainder = (size % unitSize).toInt()
        return if (remainder == 0) 0 else unitSize - remainder
    }
}

private sealed class Node(val name: String) {
    var startSector: Int = ENDOFCHAIN
    var size: Long = 0L
}

private class StorageNode(name: String) : Node(name) {
    val children: TreeMap<String, Node> = TreeMap(CFB_NAME_COMPARATOR)
}

private class StreamNode(name: String, val spool: StreamSpool) : Node(name) {
    var sectorCount: Int = 0
}

private sealed interface StreamSpool : AutoCloseable {
    val size: Long

    fun openOutput(): OutputStream

    fun openInput(): InputStream
}

private class TemporaryFileStreamSpool(directory: File) : StreamSpool {
    private val file = File.createTempFile("compound-file-kt-", ".stream", directory)

    override val size: Long
        get() = file.length()

    override fun openOutput(): OutputStream = BufferedOutputStream(FileOutputStream(file))

    override fun openInput(): InputStream = BufferedInputStream(FileInputStream(file))

    override fun close() {
        if (file.exists()) file.delete()
    }
}

private class MemoryStreamSpool : StreamSpool {
    private val output = SpoolByteArrayOutputStream()

    override val size: Long
        get() = output.byteCount.toLong()

    override fun openOutput(): OutputStream = output

    override fun openInput(): InputStream = output.openInput()

    override fun close() {
        output.discard()
    }
}

private class SpoolByteArrayOutputStream : ByteArrayOutputStream() {
    val byteCount: Int
        get() = count

    fun openInput(): InputStream = ByteArrayInputStream(buf, 0, count)

    fun discard() {
        buf = ByteArray(0)
        count = 0
    }
}

private class DirectoryRecord(val id: Int, val node: Node) {
    var left: Int = NOSTREAM
    var right: Int = NOSTREAM
    var child: Int = NOSTREAM
    var parent: Int = NOSTREAM
    var red: Boolean = false
}
