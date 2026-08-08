# Compound File capability

## Purpose

Define the initial public contract for a Kotlin Android library with minSdk 24 that
opens existing Microsoft Compound File Binary (CFB/OLE2) containers and creates
new version 3 or version 4 containers without taking ownership of caller-provided
channels.

## Requirements

### Requirement: Android platform baseline

The library SHALL be published as an Android library (`.aar`) with Android API
24 as its minimum supported SDK. The library SHALL NOT promise compatibility as
a standalone Java or Kotlin/JVM library.

#### Scenario: Android application consumes the library

- **WHEN** an Android application with minSdk 24 or newer adds the Maven dependency
- **THEN** it can consume the library's release AAR and use its public API

#### Scenario: Java-only artifact is inspected

- **WHEN** a consumer looks for a standalone JVM JAR compatibility guarantee
- **THEN** no such compatibility guarantee is part of the initial contract

### Requirement: Published identity

The library SHALL be named `CompoundFileKt`, use Maven artifact ID
`compound-file-kt`, use Maven group ID `io.github.limarev`, and place its public
API in package `io.github.limarev.compoundfile`. The primary public entry point
SHALL be the Kotlin object `CompoundFile`.

#### Scenario: Consumer imports the entry point

- **WHEN** a consumer adds dependency `io.github.limarev:compound-file-kt:<version>`
- **THEN** the consumer can import `io.github.limarev.compoundfile.CompoundFile`

### Requirement: Supported format versions

The library SHALL expose `CfbVersion` with exactly the initially supported
values `V3` and `V4`. Creation SHALL require the caller to select a version.

#### Scenario: Create a version 3 container

- **WHEN** a caller invokes `CompoundFile.create` with `CfbVersion.V3`
- **THEN** the library creates a version 3 CFB container

#### Scenario: Create a version 4 container

- **WHEN** a caller invokes `CompoundFile.create` with `CfbVersion.V4`
- **THEN** the library creates a version 4 CFB container

### Requirement: Distinct read and write storage types

The library SHALL represent an opened storage with `ReadableStorage` and a
storage being created with `WritableStorage`. Neither interface SHALL expose
the operations of the other mode.

#### Scenario: Read block API surface

- **WHEN** code executes in a `CompoundFile.open` receiver block
- **THEN** its storage receiver exposes only `openStorage` and `openStream`

#### Scenario: Write block API surface

- **WHEN** code executes in a `CompoundFile.create` or `createStorage` receiver block
- **THEN** its storage receiver exposes only `createStorage` and `createStream`

### Requirement: Open a container from a positioned channel

`CompoundFile.open(source, block)` SHALL treat the entry position of the
`SeekableByteChannel` as byte zero of the CFB container. The source SHALL support
reading, positioning, and size queries. The function SHALL execute `block` with
the root `ReadableStorage` receiver and return the value produced by `block`.

#### Scenario: Embedded container is opened

- **WHEN** a readable seekable channel is positioned after a byte prefix and `open` is called
- **THEN** the CFB signature and all sector offsets are interpreted relative to that entry position
- **AND** bytes before the entry position are not interpreted as part of the container

#### Scenario: Read block returns a value

- **WHEN** the read block completes with a value
- **THEN** `open` returns that same value

### Requirement: Read channel ownership

`CompoundFile.open` SHALL leave the caller-owned source channel open. Its
position after the operation is unspecified.

#### Scenario: Successful open leaves the source open

- **WHEN** the read block and container processing complete successfully
- **THEN** the source remains open

#### Scenario: Failed open leaves the source open

- **WHEN** parsing, I/O, stream use, or the caller's read block throws
- **THEN** the source remains open
- **AND** the failure is propagated

### Requirement: Direct-child storage lookup

`ReadableStorage.openStorage(name)` SHALL open a direct child storage with the
requested CFB name, or return `null` when that direct child is absent or has a
non-storage entry kind. It SHALL propagate invalid-name, corrupt-input,
truncated-input, and I/O failures.

#### Scenario: Child storage exists

- **WHEN** `openStorage` names a direct child storage
- **THEN** the corresponding `ReadableStorage` is returned

#### Scenario: Storage is absent or has the wrong kind

- **WHEN** `openStorage` names no direct child storage, including when a stream has that name
- **THEN** it returns `null`

#### Scenario: Slash-delimited text is supplied

- **WHEN** `openStorage` receives a name containing path-like separators
- **THEN** the complete string is treated as one directory-entry name and is not traversed as a path

#### Scenario: Storage lookup encounters malformed input

- **WHEN** `openStorage` encounters corruption, truncation, an invalid name, or an I/O failure
- **THEN** it throws the corresponding failure instead of returning `null`

### Requirement: Direct-child stream lookup

`ReadableStorage.openStream(name)` SHALL open a direct child stream with the
requested CFB name and return it as an `InputStream`, or return `null` when that
direct child is absent or has a non-stream entry kind. It SHALL propagate
invalid-name, corrupt-input, truncated-input, and I/O failures.

#### Scenario: Child stream exists

- **WHEN** `openStream` names a direct child stream
- **THEN** an `InputStream` for that stream is returned

#### Scenario: Stream is absent or has the wrong kind

- **WHEN** `openStream` names no direct child stream, including when a storage has that name
- **THEN** it returns `null`

#### Scenario: Stream lookup encounters malformed input

- **WHEN** `openStream` encounters corruption, truncation, an invalid name, or an I/O failure
- **THEN** it throws the corresponding failure instead of returning `null`

### Requirement: Scoped readable resources

The root storage, descendant readable storages, and opened input streams SHALL
be valid only while the enclosing `CompoundFile.open` block is active. The
caller SHALL close each returned `InputStream` that it opens.

#### Scenario: Stream is consumed within the open block

- **WHEN** a caller opens, consumes, and closes an input stream before the enclosing read block ends
- **THEN** the stream content is available and the source channel remains caller-owned

#### Scenario: Read resource escapes its scope

- **WHEN** code attempts to use a readable storage or input stream after the enclosing read block ends
- **THEN** the resource rejects use rather than accessing the source channel

### Requirement: Incremental stream reading

The library SHALL read CFB stream content incrementally and SHALL NOT require
the whole container or whole logical stream to be materialized in one
`ByteArray`.

#### Scenario: Large stream is copied

- **WHEN** a caller uses `InputStream.copyTo` on a logical CFB stream larger than the implementation buffer
- **THEN** the content is delivered in bounded chunks in logical stream order

### Requirement: Create a container at a positioned channel

`CompoundFile.create(destination, version, spoolingPolicy, block)` SHALL begin
the CFB container at the destination channel's entry position. The destination
SHALL support writing, positioning, size queries, and truncation. The function
SHALL execute `block` with the root `WritableStorage` receiver.

#### Scenario: Embedded container is created

- **WHEN** a writable seekable channel is positioned after an existing prefix and `create` is called
- **THEN** the new CFB container begins at that entry position
- **AND** bytes before the entry position remain unchanged

### Requirement: Write channel ownership and failure state

`CompoundFile.create` SHALL leave the caller-owned destination open. Its position
after the operation is unspecified. If creation, caller code, or finalization
fails, the function SHALL propagate the failure; partial output is permitted.

#### Scenario: Successful creation leaves the destination open

- **WHEN** the write block and finalization complete successfully
- **THEN** the destination remains open

#### Scenario: Creation fails

- **WHEN** writing, caller code, or finalization throws
- **THEN** the destination remains open and the failure is propagated
- **AND** the destination may contain partial output

### Requirement: Scoped storage creation

`WritableStorage.createStorage(name, block)` SHALL create a direct child
storage, execute `block` with that child's `WritableStorage` receiver, and
finalize the child when the block completes. The child SHALL NOT be usable
after its block ends.

#### Scenario: Nested storage completes

- **WHEN** a `createStorage` block returns normally
- **THEN** the child storage and all completed descendants are finalized

#### Scenario: Writable storage escapes its scope

- **WHEN** code attempts to use a writable child storage after its block ends
- **THEN** the child rejects further operations

### Requirement: Scoped stream creation

`WritableStorage.createStream(name, block)` SHALL create a direct child stream,
execute `block` with an `OutputStream` receiver, and finalize that output when
the block completes. The output SHALL NOT be usable after its block ends, and
the stream receiver SHALL expose byte-output operations rather than storage
creation operations.

#### Scenario: Stream block writes content

- **WHEN** the caller writes bytes inside a `createStream` block and the block returns normally
- **THEN** the child stream is finalized with those bytes in their written order

#### Scenario: Output stream escapes its scope

- **WHEN** code attempts to use the output stream after its `createStream` block ends
- **THEN** the output stream rejects further writes

### Requirement: Incremental stream writing

The library SHALL write logical CFB stream content incrementally and SHALL NOT
require the whole container or whole logical stream to be materialized in one
`ByteArray` when temporary-file spooling is selected.

### Requirement: Configurable write spooling

The library SHALL let callers choose whether logical stream content is staged in
temporary files or heap memory while the final CFB layout is assembled.
The caller SHALL choose the policy explicitly. In-memory spooling SHALL NOT
create temporary files and SHALL retain staged logical stream content in heap
memory until finalization completes.

#### Scenario: In-memory-only staging

- **WHEN** a caller creates a container with `CfbSpoolingPolicy.InMemory`
- **THEN** logical stream content is staged without temporary-file I/O
- **AND** the completed container is written to the caller-provided destination

#### Scenario: Selected temporary directory

- **WHEN** a caller selects `CfbSpoolingPolicy.TemporaryFiles(directory)`
- **THEN** logical streams are staged under that directory
- **AND** the temporary files are removed when creation succeeds or fails

#### Scenario: Large source is copied into a stream

- **WHEN** a caller uses `InputStream.copyTo` inside `createStream` with content larger than the implementation buffer
- **THEN** the output accepts bounded chunks and produces the complete logical stream in order

### Requirement: Exact CFB entry names

All storage and stream operations SHALL treat names as exact CFB
directory-entry names subject to CFB's validity and comparison rules. The
library SHALL preserve valid Unicode code units, including leading U+0006 and
U+0009 control characters, and SHALL NOT trim, normalize, or reinterpret them
as paths.

#### Scenario: Control-character-prefixed names round-trip

- **WHEN** a container is created with entries named `\u0006DataSpaces`, `\u0009DRMTransform`, or `\u0006Primary`
- **THEN** opening the resulting container with the same exact names finds those entries

#### Scenario: Similar name is not substituted

- **WHEN** a lookup omits or changes a leading control character from an existing name
- **THEN** the differently named entry is not returned unless CFB comparison rules define the names as equal

### Requirement: Duplicate sibling names

Creation SHALL reject a new direct child when the parent already has a child
with the same name according to CFB name-comparison rules, regardless of whether
the existing child is a storage or a stream. The failure SHALL be
`CfbEntryAlreadyExistsException` with its `entryName` set to the requested name.

#### Scenario: Duplicate stream or storage is created

- **WHEN** `createStorage` or `createStream` requests a CFB-comparable name already used by a sibling
- **THEN** `CfbEntryAlreadyExistsException` is thrown with `entryName` equal to the requested name

### Requirement: Public failure hierarchy

The library SHALL expose `CfbException` as an open subclass of `IOException`
with a message. It SHALL expose `InvalidCfbException`, `CorruptCfbException`,
and `TruncatedCfbException` as subclasses accepting a message. It SHALL expose
`CfbEntryAlreadyExistsException` as a subclass carrying the requested
`entryName`.

#### Scenario: Input is not a CFB container

- **WHEN** the source lacks valid CFB identification
- **THEN** `InvalidCfbException` is thrown

#### Scenario: Identified CFB has invalid internal structure

- **WHEN** valid CFB identification is followed by structurally corrupt metadata or allocation data
- **THEN** `CorruptCfbException` is thrown

#### Scenario: Required input is incomplete

- **WHEN** the source ends before a required or declared CFB structure is complete
- **THEN** `TruncatedCfbException` is thrown

#### Scenario: Duplicate-entry exception identifies the name

- **WHEN** a duplicate-entry exception is constructed for a name
- **THEN** its message identifies that name and its `entryName` property retains the exact string

### Requirement: Complete initial public API surface

The initial release SHALL expose the following declarations and no additional
whole-container convenience or mutation API:

```kotlin
public enum class CfbVersion { V3, V4 }

public sealed interface CfbSpoolingPolicy {
    public data object InMemory : CfbSpoolingPolicy
    public data class TemporaryFiles(public val directory: File) : CfbSpoolingPolicy
}

public interface ReadableStorage {
    public fun openStorage(name: String): ReadableStorage?
    public fun openStream(name: String): InputStream?
}

public interface WritableStorage {
    public fun createStorage(name: String, block: WritableStorage.() -> Unit)
    public fun createStream(name: String, block: OutputStream.() -> Unit)
}

public object CompoundFile {
    public fun <R> open(
        source: SeekableByteChannel,
        block: ReadableStorage.() -> R,
    ): R

    public fun create(
        destination: SeekableByteChannel,
        version: CfbVersion,
        spoolingPolicy: CfbSpoolingPolicy,
        block: WritableStorage.() -> Unit,
    )
}

public open class CfbException(message: String) : IOException(message)
public class InvalidCfbException(message: String) : CfbException(message)
public class CorruptCfbException(message: String) : CfbException(message)
public class TruncatedCfbException(message: String) : CfbException(message)
public class CfbEntryAlreadyExistsException(public val entryName: String) : CfbException
```

#### Scenario: Initial API compatibility is checked

- **WHEN** the initial release's public API is compared with this declaration list
- **THEN** every listed declaration, parameter type, receiver type, return type, and inheritance relationship is present

### Requirement: Initial release excludes mutation and convenience extensions

The initial release SHALL NOT expose directory enumeration or metadata APIs,
in-place container modification, entry deletion, movement or renaming, writable
streams for in-place mutation, whole-container `ByteArray` convenience
overloads, or a library-owned in-memory seekable channel.

#### Scenario: Consumer inspects the initial API

- **WHEN** a consumer inspects the initial public API surface
- **THEN** none of the deferred extension APIs are present
