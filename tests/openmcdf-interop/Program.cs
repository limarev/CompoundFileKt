using OpenMcdf;
using CfbVersion = OpenMcdf.Version;

if (args.Length != 3 || (args[0] != "create" && args[0] != "verify"))
{
    Console.Error.WriteLine("Usage: openmcdf-interop <create|verify> <v3|v4> <path>");
    return 2;
}

var version = args[1] switch
{
    "v3" => CfbVersion.V3,
    "v4" => CfbVersion.V4,
    _ => throw new ArgumentException("Version must be v3 or v4"),
};
var path = Path.GetFullPath(args[2]);
var small = Enumerable.Range(0, 777).Select(i => (byte)(i * 17 + 3)).ToArray();
var large = Enumerable.Range(0, 20_000).Select(i => (byte)(i * 31 + 11)).ToArray();
int[] boundarySizes = [0, 63, 64, 65, 511, 512, 513, 4095, 4096, 4097];

if (args[0] == "create")
{
    Directory.CreateDirectory(Path.GetDirectoryName(path)!);
    using var root = RootStorage.Create(path, version, StorageModeFlags.StrictValidation);
    WriteStream(root, "Small", small);
    WriteStream(root, "Large", large);
    WriteStream(root, "Empty", Array.Empty<byte>());
    foreach (var size in boundarySizes)
        WriteStream(root, BoundaryName(size), BoundaryBytes(size));
    var dataSpaces = root.CreateStorage("\u0006DataSpaces");
    var transformInfo = dataSpaces.CreateStorage("TransformInfo");
    var transform = transformInfo.CreateStorage("\u0009DRMTransform");
    WriteStream(transform, "\u0006Primary", new byte[] { 9, 8, 7, 6 });
    root.Flush(consolidate: true);
}
else
{
    using var root = RootStorage.OpenRead(path, StorageModeFlags.StrictValidation);
    AssertBytes(root.OpenStream("Small"), small, "Small");
    AssertBytes(root.OpenStream("Large"), large, "Large");
    AssertBytes(root.OpenStream("Empty"), Array.Empty<byte>(), "Empty");
    foreach (var size in boundarySizes)
        AssertBytes(root.OpenStream(BoundaryName(size)), BoundaryBytes(size), BoundaryName(size));
    if (root.ContainsEntry("Huge"))
        AssertPattern(root.OpenStream("Huge"), 8 * 1024 * 1024 + 123, "Huge");
    var primary = root
        .OpenStorage("\u0006DataSpaces")
        .OpenStorage("TransformInfo")
        .OpenStorage("\u0009DRMTransform")
        .OpenStream("\u0006Primary");
    AssertBytes(primary, new byte[] { 9, 8, 7, 6 }, "Primary");
}

Console.WriteLine($"OpenMcdf {args[0]} {args[1]} passed: {path}");
return 0;

static void AssertBytes(Stream stream, byte[] expected, string name)
{
    using (stream)
    using (var memory = new MemoryStream())
    {
        stream.CopyTo(memory);
        if (!memory.ToArray().SequenceEqual(expected))
            throw new InvalidDataException($"Unexpected bytes in {name}");
    }
}

static void WriteStream(Storage storage, string name, byte[] value)
{
    using var stream = storage.CreateStream(name);
    stream.Write(value);
}

static string BoundaryName(int size) => $"Boundary-{size}";

static byte[] BoundaryBytes(int size) =>
    Enumerable.Range(0, size).Select(i => (byte)(i * 29 + 5)).ToArray();

static void AssertPattern(Stream stream, int expectedLength, string name)
{
    using (stream)
    {
        var buffer = new byte[8192];
        var offset = 0;
        int count;
        while ((count = stream.Read(buffer, 0, buffer.Length)) != 0)
        {
            for (var index = 0; index < count; index++)
                if (buffer[index] != (byte)((offset + index) * 13 + 7))
                    throw new InvalidDataException($"Unexpected bytes in {name} at {offset + index}");
            offset += count;
        }
        if (offset != expectedLength)
            throw new InvalidDataException($"Unexpected length in {name}: {offset}");
    }
}
