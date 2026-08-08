package io.github.limarev.compoundfile.internal

import io.github.limarev.compoundfile.TruncatedCfbException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

internal fun SeekableByteChannel.readFullyAt(position: Long, buffer: ByteBuffer, context: String) {
    if (position < 0L) throw TruncatedCfbException("Invalid offset while reading $context")
    position(position)
    while (buffer.hasRemaining()) {
        val read = read(buffer)
        if (read < 0) throw TruncatedCfbException("Input ended while reading $context")
        if (read == 0) throw IOException("Channel made no progress while reading $context")
    }
    buffer.flip()
}

internal fun SeekableByteChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) {
        val written = write(buffer)
        if (written == 0) throw IOException("Channel made no progress while writing CFB data")
    }
}
