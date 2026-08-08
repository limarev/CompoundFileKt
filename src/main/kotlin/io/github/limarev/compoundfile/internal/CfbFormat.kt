package io.github.limarev.compoundfile.internal

import io.github.limarev.compoundfile.CorruptCfbException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

internal const val HEADER_SIZE: Int = 512
internal const val MINI_SECTOR_SIZE: Int = 64
internal const val MINI_STREAM_CUTOFF: Long = 4096L
internal const val MAX_REGULAR_SECTOR: Int = 0xFFFFFFF9.toInt()
internal const val DIFSECT: Int = 0xFFFFFFFC.toInt()
internal const val FATSECT: Int = 0xFFFFFFFD.toInt()
internal const val ENDOFCHAIN: Int = 0xFFFFFFFE.toInt()
internal const val FREESECT: Int = 0xFFFFFFFF.toInt()
internal const val NOSTREAM: Int = FREESECT

internal val SIGNATURE: ByteArray = byteArrayOf(
    0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
    0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
)

internal fun littleBuffer(size: Int): ByteBuffer =
    ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

internal fun checkedSectorId(value: Int, context: String): Int {
    if (value < 0 || value > MAX_REGULAR_SECTOR) {
        throw CorruptCfbException("Invalid sector ID $value in $context")
    }
    return value
}

internal fun ceilDiv(value: Long, divisor: Int): Int {
    if (value <= 0L) return 0
    val result = (value + divisor - 1L) / divisor
    if (result > Int.MAX_VALUE) throw CorruptCfbException("CFB structure is too large")
    return result.toInt()
}

internal fun validateName(name: String) {
    require(name.isNotEmpty()) { "CFB entry name must not be empty" }
    require(name.length <= 31) { "CFB entry name exceeds 31 UTF-16 code units: $name" }
    require(name.none { it == '\u0000' || it == '/' || it == '\\' || it == ':' || it == '!' }) {
        "Invalid CFB entry name: $name"
    }
}

internal val CFB_NAME_COMPARATOR: Comparator<String> = Comparator { left, right ->
    val byLength = left.length.compareTo(right.length)
    if (byLength != 0) byLength
    else left.uppercase(Locale.ROOT).compareTo(right.uppercase(Locale.ROOT))
}
