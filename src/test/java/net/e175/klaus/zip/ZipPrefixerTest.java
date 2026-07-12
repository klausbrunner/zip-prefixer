package net.e175.klaus.zip;

import static net.e175.klaus.zip.TestUtil.prepareTestFile;
import static net.e175.klaus.zip.ZipPrefixer.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ZipPrefixerTest {

  @Test
  void appliesPrefixes() throws IOException {
    Path f = prepareTestFile("bla.txt");

    long prefixLength =
        applyPrefixBytes(
            f,
            Arrays.asList(
                "Lorem ".getBytes(StandardCharsets.UTF_8),
                "ipsum ".getBytes(StandardCharsets.UTF_8)));

    List<String> strings = Files.readAllLines(f);
    assertEquals("Lorem ipsum dolor sit.", strings.get(0));

    assertEquals(12, prefixLength);
  }

  @Test
  void appliesPrefixFile() throws IOException {
    Path f = prepareTestFile("bla.txt");
    Path f2 = prepareTestFile("bla.txt");

    long prefixLength = applyPrefixFiles(f, Collections.singletonList(f2));

    List<String> strings = Files.readAllLines(f);
    assertEquals("dolor sit.dolor sit.", strings.get(0));

    assertEquals(f2.toFile().length(), prefixLength);
  }

  @Test
  void validatesZipOffsets() throws IOException {
    Path f = prepareTestFile("simplest.jar");

    TestUtil.looksLikeGoodZip(f);
    adjustZipOffsets(f, 0);
  }

  @Test
  void detectsBadOffsets() throws IOException {
    Path f = prepareTestFile("simplest-zip64.jar");

    TestUtil.looksLikeGoodZip(f);
    validateZipOffsets(f);
    applyPrefixBytes(f, Collections.singletonList("broken".getBytes(StandardCharsets.UTF_8)));
    try {
      validateZipOffsets(f);
      fail("should have thrown an exception");
    } catch (IOException ignored) {
    }
  }

  @ParameterizedTest
  @Tag("HeavyTest")
  @ValueSource(
      strings = {
        "simplest.jar",
        "simplest-zip64.jar",
        "single-1g-file.zip",
        "single-10g-file.zip",
        "2k-tiny-files.zip",
        "20k-tiny-files.zip",
        "small-forced-zip64.zip",
        "small-forced-zip64-python.zip",
        "zip64-golang.zip",
        "winzip-normal.zip",
        "winzip-zipx.zip"
      })
  void adjustsZipOffsets(String filename) throws IOException {
    Path f = prepareTestFile(filename);

    TestUtil.looksLikeGoodZip(f);

    final byte[] prefix = "0123456789".getBytes(StandardCharsets.UTF_8);

    applyPrefixBytesToZip(f, prefix);

    validateZipOffsets(f);
    TestUtil.looksLikeGoodZip(f);
  }

  @ParameterizedTest
  @Tag("HeavyTest")
  @ValueSource(strings = {"few-huge-files.zip", "100k-files.zip"})
  void adjustsZipOffsetsOnHugeFiles(String filename) throws IOException {
    Path f = prepareTestFile(filename);
    validateZipOffsets(f);

    final byte[] prefix = "0123456789".getBytes(StandardCharsets.UTF_8);

    assertEquals(prefix.length, applyPrefixBytes(f, Collections.singletonList(prefix)));

    try {
      validateZipOffsets(f);
      fail("should have thrown an exception, but didn't");
    } catch (IOException ignored) {
    }

    adjustZipOffsets(f, prefix.length);

    validateZipOffsets(f);
  }

  @Test
  @Tag("HeavyTest")
  void bailsOutOn4gBoundaryCrossing() throws IOException {
    Path filler = prepareTestFile("1g-file.bin");
    Path zip = prepareTestFile("simplest.jar");

    validateZipOffsets(zip);

    assertThrows(
        ZipOverflowException.class,
        () -> applyPrefixFilesToZip(zip, Arrays.asList(filler, filler, filler, filler)));
  }

  @Test
  void validatesZipOffsets64() throws IOException {
    Path f = prepareTestFile("simplest-zip64.jar");

    TestUtil.looksLikeGoodZip(f);
    adjustZipOffsets(f, 0);
  }

  @Test
  void detectsNonZips() throws IOException {
    Path f = prepareTestFile("simplest-zip64.jar");

    ZipPrefixer.looksLikeZip(f);

    Path f2 = prepareTestFile("bla.txt");
    assertThrows(ZipException.class, () -> ZipPrefixer.looksLikeZip(f2));
  }

  @Test
  void ignoresEocdrSignatureInsideZipComment() throws IOException {
    Path zip = Files.createTempFile("zip-prefixer-eocdr-comment", ".zip");
    try {
      try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
        out.putNextEntry(new ZipEntry("a.txt"));
        out.write('x');
        out.closeEntry();

        byte[] falseEocdr = new byte[EOCDR.size() + 4];
        falseEocdr[0] = 0x50;
        falseEocdr[1] = 0x4b;
        falseEocdr[2] = 0x05;
        falseEocdr[3] = 0x06;
        Arrays.fill(falseEocdr, EOCDR.size(), falseEocdr.length, (byte) 'x');
        out.setComment(new String(falseEocdr, StandardCharsets.ISO_8859_1));
      }

      byte[] prefix = "prefix".getBytes(StandardCharsets.UTF_8);
      assertEquals(prefix.length, applyPrefixBytesToZip(zip, prefix));
      validateZipOffsets(zip);
      try (var prefixedZip = new ZipFile(zip.toFile())) {
        assertNotNull(prefixedZip.getEntry("a.txt"));
      }
    } finally {
      Files.deleteIfExists(zip);
    }
  }

  @Test
  void rejectsNonRegularFiles() throws IOException {
    Path dir = Files.createTempDirectory("zip-prefixer-dir");
    try {
      assertThrows(IOException.class, () -> ZipPrefixer.isUsableFile(dir));
    } finally {
      Files.deleteIfExists(dir);
    }
  }

  @Test
  void prefixesRelativePaths() throws IOException {
    Path source = prepareTestFile("simplest.jar");
    Path relative = Paths.get("relative-" + System.nanoTime() + ".jar");
    try {
      Files.copy(source, relative);
      byte[] prefix = "rel".getBytes(StandardCharsets.UTF_8);
      applyPrefixBytesToZip(relative, prefix);
      validateZipOffsets(relative);
      TestUtil.looksLikeGoodZip(relative);
    } finally {
      Files.deleteIfExists(relative);
    }
  }

  @Test
  void preservesPermissionsAndTimestamps() throws IOException {
    Path target = Files.createTempFile("zip-prefixer-perms", ".bin");
    try {
      Files.writeString(target, "data", StandardCharsets.UTF_8);
      FileTime timestamp = FileTime.fromMillis(123456789_000L);
      BasicFileAttributeView view =
          Files.getFileAttributeView(target, BasicFileAttributeView.class);
      view.setTimes(timestamp, timestamp, timestamp);

      boolean posixSupported =
          FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
      Set<PosixFilePermission> permissions = null;
      if (posixSupported) {
        permissions = PosixFilePermissions.fromString("rwxr-x---");
        Files.setPosixFilePermissions(target, permissions);
      }

      applyPrefixBytes(target, Collections.singletonList("abc".getBytes(StandardCharsets.UTF_8)));

      assertEquals(timestamp, Files.getLastModifiedTime(target));
      if (posixSupported) {
        assertEquals(permissions, Files.getPosixFilePermissions(target));
      }
    } finally {
      Files.deleteIfExists(target);
    }
  }

  @Test
  void followsSymlinksWhenPrefixing() throws IOException {
    Path source = prepareTestFile("simplest.jar");
    Path dir = Files.createTempDirectory("zip-prefixer-symlink");
    Path real = dir.resolve("real.jar");
    Files.copy(source, real);
    Path link = dir.resolve("link.jar");
    try {
      Files.createSymbolicLink(link, real.getFileName());
    } catch (IOException | UnsupportedOperationException e) {
      Files.deleteIfExists(real);
      Files.deleteIfExists(dir);
      Assumptions.assumeTrue(false, "Symlinks unsupported: " + e.getMessage());
      return;
    }

    try {
      byte[] prefix = "0123456789".getBytes(StandardCharsets.UTF_8);
      long originalSize = Files.size(real);
      applyPrefixBytesToZip(link, prefix);
      assertTrue(Files.isSymbolicLink(link));
      assertEquals(originalSize + prefix.length, Files.size(real));
      assertEquals(Files.size(real), Files.size(link));
      validateZipOffsets(real);
      TestUtil.looksLikeGoodZip(real);
    } finally {
      Files.deleteIfExists(link);
      Files.deleteIfExists(real);
      Files.deleteIfExists(dir);
    }
  }

  @Test
  void findsZip64ExtraFieldPastFilename() throws Exception {
    byte[] fileName = "abcdefghij".getBytes(StandardCharsets.US_ASCII);
    long expectedOffset = 0x0102_0304_0506_0708L;
    byte[] extraField =
        ByteBuffer.allocate(2 + 2 + 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort((short) 0x0001)
            .putShort((short) 8)
            .putLong(expectedOffset)
            .array();

    Path temp = Files.createTempFile("zip-prefixer-cfh", ".bin");
    try {
      ByteBuffer cfhBuffer = ZipPrefixer.CFH.bufferFor();
      cfhBuffer.putInt(0x02014b50); // signature
      cfhBuffer.putShort((short) 0); // versionMadeBy
      cfhBuffer.putShort((short) 0); // versionNeededToExtract
      cfhBuffer.putShort((short) 0); // flags
      cfhBuffer.putShort((short) 0); // compression
      cfhBuffer.putShort((short) 0); // time
      cfhBuffer.putShort((short) 0); // date
      cfhBuffer.putInt(0); // crc
      cfhBuffer.putInt(0); // compressed size
      cfhBuffer.putInt(0); // uncompressed size
      cfhBuffer.putShort((short) fileName.length);
      cfhBuffer.putShort((short) extraField.length);
      cfhBuffer.putShort((short) 0); // comment length
      cfhBuffer.putShort((short) 0); // disk start
      cfhBuffer.putShort((short) 0); // internal attr
      cfhBuffer.putInt(0); // external attr
      cfhBuffer.putInt(0xFFFF_FFFF); // relative offset (needs ZIP64)
      cfhBuffer.flip();

      try (var channel =
          Files.newByteChannel(
              temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        channel.write(cfhBuffer);
        channel.write(ByteBuffer.wrap(fileName));
        channel.write(ByteBuffer.wrap(extraField));
      }

      try (var channel = Files.newByteChannel(temp, StandardOpenOption.READ)) {
        ByteBuffer readBuf = ZipPrefixer.CFH.bufferFor();
        var cfh =
            BinaryMapper.read(ZipPrefixer.CFH, channel, 0, readBuf)
                .orElseThrow(RuntimeException::new);
        Method method =
            ZipPrefixer.class.getDeclaredMethod(
                "processLfhOffset",
                long.class,
                BinaryMapper.PatternInstance.class,
                long.class,
                int.class,
                boolean.class,
                long.class,
                Queue.class,
                SeekableByteChannel.class);
        method.setAccessible(true);
        long result =
            (long)
                method.invoke(
                    null,
                    0xFFFF_FFFFL,
                    cfh,
                    0L,
                    extraField.length,
                    false,
                    0L,
                    new ArrayDeque<BinaryMapper.Write>(),
                    channel);
        assertEquals(expectedOffset, result);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
