package io.github.limarev.compoundfile.internal

import io.github.limarev.compoundfile.CorruptCfbException
import io.github.limarev.compoundfile.InvalidCfbException
import io.github.limarev.compoundfile.ReadableStorage
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import kotlin.math.min

internal class CfbReader(
    private val source: SeekableByteChannel,
) {
    private val basePosition = source.position()
    private val sectorSize: Int
    private val sectorCount: Int
    private val version: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<DirectoryEntry>
    private val rootMiniChain: IntArray
    private var active: Boolean = true

    init {
        val header = littleBuffer(HEADER_SIZE)
        synchronized(source) {
            source.readFullyAt(basePosition, header, "CFB header")
        }
        val signature = ByteArray(SIGNATURE.size)
        header.get(signature)
        if (!signature.contentEquals(SIGNATURE)) {
            throw InvalidCfbException("Input does not begin with the CFB signature")
        }
        header.position(24)
        val minorVersion = header.short.toInt() and 0xFFFF
        version = header.short.toInt() and 0xFFFF
        val byteOrder = header.short.toInt() and 0xFFFF
        val sectorShift = header.short.toInt() and 0xFFFF
        val miniSectorShift = header.short.toInt() and 0xFFFF
        if (minorVersion != 0x003E) throw CorruptCfbException("Unsupported CFB minor version: $minorVersion")
        if (version != 3 && version != 4) throw CorruptCfbException("Unsupported CFB major version: $version")
        if (byteOrder != 0xFFFE) throw CorruptCfbException("Invalid CFB byte order: $byteOrder")
        if ((version == 3 && sectorShift != 9) || (version == 4 && sectorShift != 12)) {
            throw CorruptCfbException("Invalid sector shift $sectorShift for CFB version $version")
        }
        if (miniSectorShift != 6) throw CorruptCfbException("Unsupported mini-sector shift: $miniSectorShift")
        sectorSize = 1 shl sectorShift

        val extent = source.size() - basePosition
        if (extent < sectorSize.toLong()) {
            throw io.github.limarev.compoundfile.TruncatedCfbException("Input is shorter than the CFB header sector")
        }
        val count = extent / sectorSize - 1L
        if (count > Int.MAX_VALUE) throw CorruptCfbException("CFB contains too many sectors")
        sectorCount = count.toInt()

        header.position(40)
        val declaredDirectorySectors = header.int
        val declaredFatSectors = nonNegativeCount(header.int, "FAT sector count")
        val firstDirectorySector = header.int
        header.int // transaction signature
        val miniStreamCutoff = header.int.toLong() and 0xFFFFFFFFL
        val firstMiniFatSector = header.int
        val declaredMiniFatSectors = nonNegativeCount(header.int, "miniFAT sector count")
        val firstDifatSector = header.int
        val declaredDifatSectors = nonNegativeCount(header.int, "DIFAT sector count")
        if (declaredFatSectors > sectorCount) throw CorruptCfbException("FAT sector count exceeds the file extent")
        if (declaredMiniFatSectors > sectorCount) throw CorruptCfbException("miniFAT sector count exceeds the file extent")
        if (declaredDifatSectors > sectorCount) throw CorruptCfbException("DIFAT sector count exceeds the file extent")
        if (version == 3 && declaredDirectorySectors != 0) {
            throw CorruptCfbException("Version 3 header declares directory sectors")
        }
        if (miniStreamCutoff != MINI_STREAM_CUTOFF) {
            throw CorruptCfbException("Unsupported mini-stream cutoff: $miniStreamCutoff")
        }

        val fatSectorIds = ArrayList<Int>(declaredFatSectors)
        repeat(109) {
            val sector = header.int
            if (sector != FREESECT) fatSectorIds += requirePhysicalSector(sector, "header DIFAT")
        }
        var difatSector = firstDifatSector
        val visitedDifat = HashSet<Int>()
        repeat(declaredDifatSectors) { index ->
            val id = requirePhysicalSector(difatSector, "DIFAT chain")
            if (!visitedDifat.add(id)) throw CorruptCfbException("Cycle in DIFAT sector chain")
            val difat = readSector(id, "DIFAT sector $index")
            repeat(sectorSize / 4 - 1) {
                val sector = difat.int
                if (sector != FREESECT) fatSectorIds += requirePhysicalSector(sector, "DIFAT entry")
            }
            difatSector = difat.int
        }
        if (declaredDifatSectors == 0 && firstDifatSector != ENDOFCHAIN && firstDifatSector != FREESECT) {
            throw CorruptCfbException("Unexpected first DIFAT sector")
        }
        if (declaredDifatSectors > 0 && difatSector != ENDOFCHAIN) {
            throw CorruptCfbException("DIFAT chain does not end after its declared sector count")
        }
        if (fatSectorIds.size != declaredFatSectors) {
            throw CorruptCfbException("Header declares $declaredFatSectors FAT sectors but DIFAT lists ${fatSectorIds.size}")
        }
        if (fatSectorIds.toSet().size != fatSectorIds.size) {
            throw CorruptCfbException("DIFAT contains duplicate FAT sector IDs")
        }

        val fatValues = IntArray(declaredFatSectors * (sectorSize / 4))
        var fatIndex = 0
        for (sector in fatSectorIds) {
            val buffer = readSector(sector, "FAT sector")
            while (buffer.hasRemaining()) fatValues[fatIndex++] = buffer.int
        }
        if (fatValues.size < sectorCount) throw CorruptCfbException("FAT does not cover all physical sectors")
        fat = fatValues
        for (sector in fatSectorIds) {
            if (fat[sector] != FATSECT) throw CorruptCfbException("FAT sector $sector is not marked FATSECT")
        }

        val directoryChain = readChain(firstDirectorySector, "directory")
        if (version == 4 && declaredDirectorySectors != directoryChain.size) {
            throw CorruptCfbException(
                "Header declares $declaredDirectorySectors directory sectors but chain has ${directoryChain.size}",
            )
        }
        entries = readDirectoryEntries(directoryChain)
        if (entries.isEmpty() || entries[0].type != EntryType.ROOT) {
            throw CorruptCfbException("Directory entry zero is not the root storage")
        }

        val miniFatChain = if (declaredMiniFatSectors == 0) {
            if (firstMiniFatSector != ENDOFCHAIN && firstMiniFatSector != FREESECT) {
                throw CorruptCfbException("Unexpected first miniFAT sector")
            }
            IntArray(0)
        } else {
            val chain = readChain(firstMiniFatSector, "miniFAT")
            if (chain.size != declaredMiniFatSectors) {
                throw CorruptCfbException(
                    "Header declares $declaredMiniFatSectors miniFAT sectors but chain has ${chain.size}",
                )
            }
            chain
        }
        miniFat = IntArray(miniFatChain.size * (sectorSize / 4))
        var miniFatIndex = 0
        for (sector in miniFatChain) {
            val buffer = readSector(sector, "miniFAT sector")
            while (buffer.hasRemaining()) miniFat[miniFatIndex++] = buffer.int
        }

        val root = entries[0]
        rootMiniChain = if (root.size == 0L) IntArray(0) else readChain(root.startSector, "root mini stream")
        val requiredRootSectors = ceilDiv(root.size, sectorSize)
        if (rootMiniChain.size < requiredRootSectors) {
            throw CorruptCfbException("Root mini-stream chain is shorter than its declared size")
        }
    }

    fun <R> useRoot(block: ReadableStorage.() -> R): R {
        try {
            return block(ReaderStorage(0))
        } finally {
            active = false
        }
    }

    private fun nonNegativeCount(value: Int, context: String): Int {
        if (value < 0) throw CorruptCfbException("$context exceeds supported range")
        return value
    }

    private fun requireActive() {
        if (!active) throw IOException("CFB read scope has ended")
    }

    private fun requirePhysicalSector(sector: Int, context: String): Int {
        if (sector < 0 || sector >= sectorCount) throw CorruptCfbException("Invalid sector $sector in $context")
        return sector
    }

    private fun sectorOffset(sector: Int): Long =
        basePosition + (sector.toLong() + 1L) * sectorSize

    private fun readSector(sector: Int, context: String): ByteBuffer {
        requirePhysicalSector(sector, context)
        val buffer = littleBuffer(sectorSize)
        synchronized(source) {
            source.readFullyAt(sectorOffset(sector), buffer, context)
        }
        return buffer
    }

    private fun readChain(firstSector: Int, context: String): IntArray {
        if (firstSector == ENDOFCHAIN) return IntArray(0)
        val result = ArrayList<Int>()
        val visited = HashSet<Int>()
        var sector = firstSector
        while (sector != ENDOFCHAIN) {
            requirePhysicalSector(sector, context)
            if (!visited.add(sector)) throw CorruptCfbException("Cycle in $context sector chain")
            result += sector
            if (result.size > sectorCount) throw CorruptCfbException("Overlong $context sector chain")
            sector = fat[sector]
            if (sector != ENDOFCHAIN && (sector < 0 || sector >= sectorCount)) {
                throw CorruptCfbException("Invalid next sector in $context chain")
            }
        }
        return result.toIntArray()
    }

    private fun readDirectoryEntries(chain: IntArray): List<DirectoryEntry> {
        if (chain.isEmpty()) throw CorruptCfbException("CFB has no directory sectors")
        val result = ArrayList<DirectoryEntry>(chain.size * sectorSize / 128)
        for (sector in chain) {
            val buffer = readSector(sector, "directory sector")
            repeat(sectorSize / 128) { result += parseDirectoryEntry(buffer, result.size) }
        }
        return result
    }

    private fun parseDirectoryEntry(buffer: ByteBuffer, id: Int): DirectoryEntry {
        val bytes = ByteArray(128)
        buffer.get(bytes)
        val entry = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        entry.position(64)
        val nameLength = entry.short.toInt() and 0xFFFF
        val typeCode = entry.get().toInt() and 0xFF
        val color = entry.get().toInt() and 0xFF
        val left = entry.int
        val right = entry.int
        val child = entry.int
        entry.position(116)
        val startSector = entry.int
        val rawSize = entry.long
        if (typeCode == 0) {
            return DirectoryEntry(id, "", EntryType.EMPTY, NOSTREAM, NOSTREAM, NOSTREAM, ENDOFCHAIN, 0L)
        }
        if (nameLength !in 2..64 || nameLength % 2 != 0 || bytes[nameLength - 2] != 0.toByte() || bytes[nameLength - 1] != 0.toByte()) {
            throw CorruptCfbException("Invalid name length in directory entry $id")
        }
        val name = bytes.copyOfRange(0, nameLength - 2).toString(Charsets.UTF_16LE)
        if (name.length > 31) throw CorruptCfbException("Directory entry $id name exceeds 31 UTF-16 code units")
        val type = when (typeCode) {
            1 -> EntryType.STORAGE
            2 -> EntryType.STREAM
            5 -> EntryType.ROOT
            else -> throw CorruptCfbException("Invalid type $typeCode in directory entry $id")
        }
        if (color !in 0..1) throw CorruptCfbException("Invalid color in directory entry $id")
        val size = if (version == 3) rawSize and 0xFFFFFFFFL else rawSize
        if (size < 0L) throw CorruptCfbException("Negative stream size in directory entry $id")
        return DirectoryEntry(id, name, type, left, right, child, startSector, size)
    }

    private fun entry(id: Int, context: String): DirectoryEntry {
        if (id < 0 || id >= entries.size) throw CorruptCfbException("Invalid directory entry ID $id in $context")
        return entries[id]
    }

    private fun findChild(parentId: Int, name: String): DirectoryEntry? {
        validateName(name)
        requireActive()
        var current = entry(parentId, "parent storage").child
        val visited = HashSet<Int>()
        while (current != NOSTREAM) {
            if (!visited.add(current)) throw CorruptCfbException("Cycle in child directory tree")
            val candidate = entry(current, "child directory tree")
            if (candidate.type == EntryType.EMPTY || candidate.type == EntryType.ROOT) {
                throw CorruptCfbException("Invalid entry type in child directory tree")
            }
            val comparison = CFB_NAME_COMPARATOR.compare(name, candidate.name)
            if (comparison == 0) return candidate
            current = if (comparison < 0) candidate.left else candidate.right
        }
        return null
    }

    private fun readRegular(sector: Int, offset: Int, target: ByteArray, targetOffset: Int, length: Int) {
        requireActive()
        requirePhysicalSector(sector, "stream")
        val buffer = ByteBuffer.wrap(target, targetOffset, length)
        synchronized(source) {
            source.readFullyAt(sectorOffset(sector) + offset, buffer, "stream data")
        }
    }

    private fun readMini(miniSector: Int, offset: Int, target: ByteArray, targetOffset: Int, length: Int) {
        if (miniSector < 0 || miniSector >= miniFat.size) {
            throw CorruptCfbException("Invalid mini-sector ID $miniSector")
        }
        val logicalOffset = miniSector.toLong() * MINI_SECTOR_SIZE + offset
        if (logicalOffset + length > entries[0].size) {
            throw CorruptCfbException("Mini-sector lies beyond the root mini stream")
        }
        val rootSectorIndex = (logicalOffset / sectorSize).toInt()
        val rootSectorOffset = (logicalOffset % sectorSize).toInt()
        if (rootSectorIndex >= rootMiniChain.size || rootSectorOffset + length > sectorSize) {
            throw CorruptCfbException("Invalid root mini-stream mapping")
        }
        readRegular(rootMiniChain[rootSectorIndex], rootSectorOffset, target, targetOffset, length)
    }

    private inner class ReaderStorage(private val entryId: Int) : ReadableStorage {
        override fun openStorage(name: String): ReadableStorage? {
            val child = findChild(entryId, name) ?: return null
            return if (child.type == EntryType.STORAGE) ReaderStorage(child.id) else null
        }

        override fun openStream(name: String): InputStream? {
            val child = findChild(entryId, name) ?: return null
            return if (child.type == EntryType.STREAM) SectorChainInputStream(child) else null
        }
    }

    private inner class SectorChainInputStream(private val stream: DirectoryEntry) : InputStream() {
        private val mini = stream.size < MINI_STREAM_CUTOFF
        private var remaining = stream.size
        private var sector = stream.startSector
        private var sectorOffset = 0
        private var closed = false
        private val visited = HashSet<Int>()

        init {
            if (remaining == 0L) {
                sector = ENDOFCHAIN
            } else {
                validateCurrentSector()
                visited += sector
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            checkBounds(bytes.size, offset, length)
            ensureOpen()
            if (length == 0) return 0
            if (remaining == 0L) return -1
            val unitSize = if (mini) MINI_SECTOR_SIZE else sectorSize
            val count = min(length.toLong(), min(remaining, (unitSize - sectorOffset).toLong())).toInt()
            if (mini) readMini(sector, sectorOffset, bytes, offset, count)
            else readRegular(sector, sectorOffset, bytes, offset, count)
            remaining -= count
            sectorOffset += count
            if (sectorOffset == unitSize && remaining > 0L) advance()
            return count
        }

        override fun skip(count: Long): Long {
            ensureOpen()
            if (count <= 0L || remaining == 0L) return 0L
            var left = min(count, remaining)
            val skipped = left
            val unitSize = if (mini) MINI_SECTOR_SIZE else sectorSize
            while (left > 0L) {
                val part = min(left, (unitSize - sectorOffset).toLong()).toInt()
                sectorOffset += part
                remaining -= part
                left -= part
                if (sectorOffset == unitSize && remaining > 0L) advance()
            }
            return skipped
        }

        override fun available(): Int {
            ensureOpen()
            return min(remaining, Int.MAX_VALUE.toLong()).toInt()
        }

        override fun close() {
            closed = true
        }

        private fun advance() {
            sector = if (mini) miniFat[sector] else fat[sector]
            sectorOffset = 0
            validateCurrentSector()
            if (!visited.add(sector)) throw CorruptCfbException("Cycle in stream sector chain")
        }

        private fun validateCurrentSector() {
            if (sector == ENDOFCHAIN) throw CorruptCfbException("Stream chain ended before its declared size")
            if (mini) {
                if (sector < 0 || sector >= miniFat.size) throw CorruptCfbException("Invalid mini-sector in stream chain")
            } else {
                requirePhysicalSector(sector, "stream chain")
            }
        }

        private fun ensureOpen() {
            requireActive()
            if (closed) throw IOException("CFB stream is closed")
        }
    }
}

private enum class EntryType {
    EMPTY,
    STORAGE,
    STREAM,
    ROOT,
}

private data class DirectoryEntry(
    val id: Int,
    val name: String,
    val type: EntryType,
    val left: Int,
    val right: Int,
    val child: Int,
    val startSector: Int,
    val size: Long,
)

private fun checkBounds(size: Int, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || length > size - offset) throw IndexOutOfBoundsException()
}
