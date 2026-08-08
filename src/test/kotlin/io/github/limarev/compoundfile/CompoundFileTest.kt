package io.github.limarev.compoundfile

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompoundFileTest {
    @Test
    fun `reads strict OpenMcdf version 3 and version 4 fixtures`() {
        for (version in listOf("v3", "v4")) {
            val resource = requireNotNull(javaClass.getResource("/openmcdf/$version.cfb"))
            FileChannel.open(Paths.get(resource.toURI()), StandardOpenOption.READ).use { channel ->
                CompoundFile.open(channel) {
                    assertContentEquals(bytes(777, 17, 3), requiredStream("Small").use(InputStream::readBytes))
                    assertContentEquals(bytes(20_000, 31, 11), requiredStream("Large").use(InputStream::readBytes))
                    assertContentEquals(byteArrayOf(), requiredStream("Empty").use(InputStream::readBytes))
                    assertBoundaryStreams()
                    val primary = requiredStorage("\u0006DataSpaces")
                        .requiredStorage("TransformInfo")
                        .requiredStorage("\u0009DRMTransform")
                        .requiredStream("\u0006Primary")
                        .use(InputStream::readBytes)
                    assertContentEquals(byteArrayOf(9, 8, 7, 6), primary)
                }
            }
        }
    }

    @Test
    fun `writes fixtures for strict OpenMcdf verification`() {
        val directory = Paths.get("build", "interop")
        Files.createDirectories(directory)
        for (version in CfbVersion.entries) {
            val label = version.name.lowercase()
            createFixture(
                directory.resolve("kotlin-$label.cfb"),
                version,
                bytes(777, 17, 3),
                bytes(20_000, 31, 11),
                includeHuge = false,
                spoolingPolicy = TEST_TEMPORARY_FILES,
            )
            createFixture(
                directory.resolve("kotlin-memory-$label.cfb"),
                version,
                bytes(777, 17, 3),
                bytes(20_000, 31, 11),
                includeHuge = false,
                spoolingPolicy = CfbSpoolingPolicy.InMemory,
            )
        }
    }

    @Test
    fun `temporary file policy uses the selected directory and cleans up`() {
        val spoolDirectory = Files.createTempDirectory("compound-file-kt-spool-")
        val path = temporaryFile()
        FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            CompoundFile.create(
                channel,
                CfbVersion.V3,
                CfbSpoolingPolicy.TemporaryFiles(spoolDirectory.toFile()),
            ) {
                createStream("stream") {
                    assertEquals(1L, directoryEntryCount(spoolDirectory))
                    write(bytes(513, 7, 3))
                }
            }
        }
        assertEquals(0L, directoryEntryCount(spoolDirectory))

        FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            assertFailsWith<IOException> {
                CompoundFile.create(
                    channel,
                    CfbVersion.V3,
                    CfbSpoolingPolicy.TemporaryFiles(spoolDirectory.toFile()),
                ) {
                    createStream("failure") { throw IOException("expected") }
                }
            }
        }
        assertEquals(0L, directoryEntryCount(spoolDirectory))
    }

    @Test
    fun `writes and reads a DIFAT-backed large version 3 container incrementally`() {
        val path = Paths.get("build", "interop", "kotlin-difat-v3.cfb")
        Files.createDirectories(path.parent)
        createFixture(
            path,
            CfbVersion.V3,
            bytes(777, 17, 3),
            bytes(20_000, 31, 11),
            includeHuge = true,
            spoolingPolicy = TEST_TEMPORARY_FILES,
        )

        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            CompoundFile.open(channel) {
                requiredStream("Huge").use { input -> assertPattern(input, HUGE_SIZE) }
            }
        }
    }

    @Test
    fun `round trips both spooling policies with mini and regular streams`() {
        for (version in CfbVersion.entries) {
            for (spoolingPolicy in listOf(TEST_TEMPORARY_FILES, CfbSpoolingPolicy.InMemory)) {
                val path = temporaryFile()
                val small = bytes(777, 17, 3)
                val large = bytes(20_000, 31, 11)
                createFixture(
                    path,
                    version,
                    small,
                    large,
                    includeHuge = false,
                    spoolingPolicy = spoolingPolicy,
                )

                FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    val result = CompoundFile.open(channel) {
                        assertContentEquals(small, requiredStream("Small").use(InputStream::readBytes))
                        assertContentEquals(large, requiredStream("Large").use(InputStream::readBytes))
                        assertContentEquals(byteArrayOf(), requiredStream("Empty").use(InputStream::readBytes))
                        assertBoundaryStreams()
                        val transformInfo = requiredStorage("\u0006DataSpaces").requiredStorage("TransformInfo")
                        val transform = transformInfo.openStorage("DRMEncryptedTransform")
                            ?: transformInfo.requiredStorage("\u0009DRMTransform")
                        assertContentEquals(
                            byteArrayOf(9, 8, 7, 6),
                            transform.requiredStream("\u0006Primary").use(InputStream::readBytes),
                        )
                        "result-$version"
                    }
                    assertEquals("result-$version", result)
                    assertTrue(channel.isOpen)
                }
            }
        }
    }

    @Test
    fun `creates and opens an embedded container without changing its prefix`() {
        for (version in CfbVersion.entries) {
            val path = temporaryFile()
            val prefix = bytes(37, 7, 1)
            Files.write(path, prefix)
            FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                channel.position(prefix.size.toLong())
                CompoundFile.create(channel, version, TEST_TEMPORARY_FILES) {
                    createStream("value") { write(byteArrayOf(1, 2, 3)) }
                }
                assertTrue(channel.isOpen)
                channel.position(prefix.size.toLong())
                val value = CompoundFile.open(channel) {
                    requiredStream("value").use(InputStream::readBytes)
                }
                assertContentEquals(byteArrayOf(1, 2, 3), value)
            }
            assertContentEquals(prefix, path.readBytes().copyOf(prefix.size))
        }
    }

    @Test
    fun `nullable lookup returns null only for absence or wrong kind`() {
        val path = temporaryFile()
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ).use { channel ->
            CompoundFile.create(channel, CfbVersion.V3, TEST_TEMPORARY_FILES) {
                createStorage("storage") {}
                createStream("stream") {}
            }
            channel.position(0L)
            CompoundFile.open(channel) {
                assertNull(openStorage("missing"))
                assertNull(openStorage("stream"))
                assertNull(openStream("missing"))
                assertNull(openStream("storage"))
                assertFailsWith<IllegalArgumentException> { openStream("") }
            }
        }
    }

    @Test
    fun `duplicate names use CFB comparison rules across entry kinds`() {
        val path = temporaryFile()
        FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val failure = assertFailsWith<CfbEntryAlreadyExistsException> {
                CompoundFile.create(channel, CfbVersion.V3, TEST_TEMPORARY_FILES) {
                    createStorage("SameName") {}
                    createStream("samename") {}
                }
            }
            assertEquals("samename", failure.entryName)
            assertTrue(channel.isOpen)
        }
    }

    @Test
    fun `read resources reject use outside their block`() {
        val path = temporaryFile()
        createFixture(
            path,
            CfbVersion.V3,
            byteArrayOf(1),
            bytes(5000, 1, 0),
            includeHuge = false,
            spoolingPolicy = TEST_TEMPORARY_FILES,
        )
        lateinit var escapedStorage: ReadableStorage
        lateinit var escapedStream: InputStream
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            CompoundFile.open(channel) {
                escapedStorage = requiredStorage("\u0006DataSpaces")
                escapedStream = requiredStream("Small")
            }
            assertFailsWith<IOException> { escapedStorage.openStorage("TransformInfo") }
            assertFailsWith<IOException> { escapedStream.read() }
        }
    }

    @Test
    fun `write resources reject use outside their block`() {
        val path = temporaryFile()
        lateinit var escapedStorage: WritableStorage
        lateinit var escapedOutput: java.io.OutputStream
        FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            CompoundFile.create(channel, CfbVersion.V3, TEST_TEMPORARY_FILES) {
                createStorage("storage") { escapedStorage = this }
                createStream("stream") { escapedOutput = this }
            }
        }
        assertFailsWith<IOException> { escapedStorage.createStream("late") {} }
        assertFailsWith<IOException> { escapedOutput.write(1) }
    }

    @Test
    fun `open preserves caller exception identity and channel ownership`() {
        val path = temporaryFile()
        val prefix = bytes(13, 2, 5)
        Files.write(path, prefix)
        FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.position(prefix.size.toLong())
            CompoundFile.create(channel, CfbVersion.V3, TEST_TEMPORARY_FILES) {
                createStream("stream") { write(1) }
            }
            channel.position(prefix.size.toLong())
            val expected = IOException("caller failure")
            val actual = assertFailsWith<IOException> {
                CompoundFile.open(channel) { throw expected }
            }
            assertSame(expected, actual)
            assertTrue(channel.isOpen)
        }
    }

    @Test
    fun `classifies invalid and truncated input`() {
        val invalid = temporaryFile()
        Files.write(invalid, ByteArray(512))
        FileChannel.open(invalid, StandardOpenOption.READ).use { channel ->
            assertFailsWith<InvalidCfbException> { CompoundFile.open(channel) {} }
            assertTrue(channel.isOpen)
        }

        val truncated = temporaryFile()
        Files.write(truncated, byteArrayOf(0xD0.toByte(), 0xCF.toByte()))
        FileChannel.open(truncated, StandardOpenOption.READ).use { channel ->
            assertFailsWith<TruncatedCfbException> { CompoundFile.open(channel) {} }
            assertTrue(channel.isOpen)
        }
    }

    @Test
    fun `exception hierarchy is based on IOException`() {
        assertIs<IOException>(CfbException("base"))
        assertIs<CfbException>(InvalidCfbException("invalid"))
        assertIs<CfbException>(CorruptCfbException("corrupt"))
        assertIs<CfbException>(TruncatedCfbException("truncated"))
        assertEquals("name", CfbEntryAlreadyExistsException("name").entryName)
    }

    private fun createFixture(
        path: Path,
        version: CfbVersion,
        small: ByteArray,
        large: ByteArray,
        includeHuge: Boolean,
        spoolingPolicy: CfbSpoolingPolicy,
    ) {
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ).use { channel ->
            CompoundFile.create(channel, version, spoolingPolicy) {
                createStream("Small") { write(small) }
                createStream("Large") { write(large) }
                createStream("Empty") {}
                for (size in BOUNDARY_SIZES) {
                    createStream(boundaryName(size)) { write(boundaryBytes(size)) }
                }
                if (includeHuge) {
                    createStream("Huge") {
                        val buffer = bytes(8192, 13, 7)
                        var remaining = HUGE_SIZE
                        while (remaining > 0) {
                            val count = minOf(buffer.size, remaining)
                            write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                }
                createStorage("\u0006DataSpaces") {
                    createStorage("TransformInfo") {
                        createStorage("\u0009DRMTransform") {
                            createStream("\u0006Primary") { write(byteArrayOf(9, 8, 7, 6)) }
                        }
                    }
                }
            }
        }
    }

    private fun temporaryFile(): Path = Files.createTempFile("compound-file-kt-test-", ".cfb")

    private fun directoryEntryCount(directory: Path): Long = Files.list(directory).use { it.count() }

    private fun bytes(size: Int, multiplier: Int, increment: Int): ByteArray =
        ByteArray(size) { (it * multiplier + increment).toByte() }

    private fun ReadableStorage.requiredStorage(name: String): ReadableStorage =
        requireNotNull(openStorage(name)) { "Missing test storage: $name" }

    private fun ReadableStorage.requiredStream(name: String): InputStream =
        requireNotNull(openStream(name)) { "Missing test stream: $name" }

    private fun ReadableStorage.assertBoundaryStreams() {
        for (size in BOUNDARY_SIZES) {
            assertContentEquals(
                boundaryBytes(size),
                requiredStream(boundaryName(size)).use(InputStream::readBytes),
                "boundary stream of $size bytes",
            )
        }
    }

    private fun boundaryName(size: Int): String = "Boundary-$size"

    private fun boundaryBytes(size: Int): ByteArray = bytes(size, 29, 5)

    private fun assertPattern(input: InputStream, expectedSize: Int) {
        val buffer = ByteArray(8192)
        var offset = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            repeat(count) { index ->
                assertEquals(((offset + index) * 13 + 7).toByte(), buffer[index], "byte ${offset + index}")
            }
            offset += count
        }
        assertEquals(expectedSize, offset)
    }

    private companion object {
        const val HUGE_SIZE = 8 * 1024 * 1024 + 123
        val TEST_TEMPORARY_FILES = CfbSpoolingPolicy.TemporaryFiles(
            File(requireNotNull(System.getProperty("java.io.tmpdir"))),
        )
        val BOUNDARY_SIZES = intArrayOf(0, 63, 64, 65, 511, 512, 513, 4095, 4096, 4097)
    }
}
