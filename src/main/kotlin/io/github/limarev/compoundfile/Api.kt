package io.github.limarev.compoundfile

import io.github.limarev.compoundfile.internal.CfbReader
import io.github.limarev.compoundfile.internal.CfbWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel

/** Supported Compound File Binary format versions. */
public enum class CfbVersion {
    V3,
    V4,
}

/** Controls where logical stream content is staged while a CFB container is assembled. */
public sealed interface CfbSpoolingPolicy {
    /** Keeps all staged stream content in heap memory and performs no temporary-file I/O. */
    public data object InMemory : CfbSpoolingPolicy

    /**
     * Stages each stream in a temporary file under [directory].
     * Files are deleted when creation ends.
     */
    public data class TemporaryFiles(public val directory: File) : CfbSpoolingPolicy
}

/** A CFB storage opened for reading. */
public interface ReadableStorage {
    /** Opens a direct child storage, or returns null if absent or not a storage. */
    public fun openStorage(name: String): ReadableStorage?

    /** Opens a direct child stream, or returns null if absent or not a stream. */
    public fun openStream(name: String): InputStream?
}

/** A CFB storage being created. */
public interface WritableStorage {
    /** Creates and finalizes a direct child storage within [block]. */
    public fun createStorage(name: String, block: WritableStorage.() -> Unit)

    /** Creates and finalizes a direct child stream within [block]. */
    public fun createStream(name: String, block: OutputStream.() -> Unit)
}

/** Entry point for opening and creating CFB containers. */
public object CompoundFile {
    /**
     * Opens a CFB container whose byte zero is [source]'s entry position.
     * The caller owns [source], which remains open.
     */
    public fun <R> open(
        source: SeekableByteChannel,
        block: ReadableStorage.() -> R,
    ): R = CfbReader(source).useRoot(block)

    /**
     * Creates a CFB container using [spoolingPolicy] for logical stream content.
     * The caller owns [destination], which remains open.
     */
    public fun create(
        destination: SeekableByteChannel,
        version: CfbVersion,
        spoolingPolicy: CfbSpoolingPolicy,
        block: WritableStorage.() -> Unit,
    ): Unit {
        CfbWriter(destination, version, spoolingPolicy).use { writer ->
            writer.build(block)
        }
    }
}

/** Base failure reported by CompoundFileKt. */
public open class CfbException(
    message: String,
) : IOException(message)

/** The input does not contain a valid CFB container. */
public class InvalidCfbException(
    message: String,
) : CfbException(message)

/** Valid CFB identification was found, but its internal structure is corrupt. */
public class CorruptCfbException(
    message: String,
) : CfbException(message)

/** The input ended before a required or declared CFB structure was complete. */
public class TruncatedCfbException(
    message: String,
) : CfbException(message)

/** A sibling with the same CFB-comparable name already exists. */
public class CfbEntryAlreadyExistsException(
    public val entryName: String,
) : CfbException("CFB entry already exists: $entryName")
