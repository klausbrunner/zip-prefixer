package net.e175.klaus.zip;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static net.e175.klaus.zip.TestUtil.prepareTestFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class ZipPrefixerTest {

    @Test
    void appliesPrefixes() throws IOException {
        Path f = prepareTestFile("bla.txt");

        long prefixLength = ZipPrefixer.applyPrefixes(f,
                "Lorem ".getBytes(StandardCharsets.UTF_8),
                "ipsum ".getBytes(StandardCharsets.UTF_8));

        List<String> strings = Files.readAllLines(f);
        assertEquals("Lorem ipsum dolor sit.", strings.get(0));

        assertEquals(12, prefixLength);
    }

    @Test
    void appliesPrefixFile() throws IOException {
        Path f = prepareTestFile("bla.txt");
        Path f2 = prepareTestFile("bla.txt");

        long prefixLength = ZipPrefixer.applyPrefixes(f, Collections.singletonList(f2));

        List<String> strings = Files.readAllLines(f);
        assertEquals("dolor sit.dolor sit.", strings.get(0));

        assertEquals(f2.toFile().length(), prefixLength);
    }

    @Test
    void validatesZipOffsets() throws IOException {
        Path f = prepareTestFile("simplest.jar");

        TestUtil.looksLikeGoodZip(f);
        ZipPrefixer.adjustZipOffsets(f, 0);
    }

    @Test
    void detectsBadOffsets() throws IOException {
        Path f = prepareTestFile("simplest-zip64.jar");

        TestUtil.looksLikeGoodZip(f);
        ZipPrefixer.validateZipOffsets(f);
        ZipPrefixer.applyPrefixes(f, "broken".getBytes(StandardCharsets.UTF_8));
        try {
            ZipPrefixer.validateZipOffsets(f);
            fail("should have thrown an exception");
        } catch(IOException ignored) {
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simplest.jar", "simplest-zip64.jar", "single-1g-file.zip", "single-10g-file.zip",
            "2k-tiny-files.zip", "20k-tiny-files.zip", "small-forced-zip64.zip", "small-forced-zip64-python.zip",
            "zip64-golang.zip"})
    void adjustsZipOffsets(String filename) throws IOException {
        Path f = prepareTestFile(filename);

        TestUtil.looksLikeGoodZip(f);

        final byte[] prefix = "0123456789".getBytes(StandardCharsets.UTF_8);

        ZipPrefixer.applyPrefixesToZip(f, prefix);

        ZipPrefixer.validateZipOffsets(f);
        TestUtil.looksLikeGoodZip(f);
    }

    @ParameterizedTest
    @Disabled("needs lots of time/disk space")
    @ValueSource(strings = {"few-huge-files.zip", "100k-files.zip"})
    void adjustsZipOffsetsOnHugeFiles(String filename) throws IOException {
        Path f = prepareTestFile(filename);
        ZipPrefixer.validateZipOffsets(f);

        final byte[] prefix = "0123456789".getBytes(StandardCharsets.UTF_8);

        assertEquals(prefix.length, ZipPrefixer.applyPrefixes(f, prefix));

        try {
            ZipPrefixer.validateZipOffsets(f);
            fail("should have thrown an exception, but didn't");
        } catch (IOException ignored) {
        }

        ZipPrefixer.adjustZipOffsets(f, prefix.length);

        ZipPrefixer.validateZipOffsets(f);
    }

    @Test
    void validatesZipOffsets64() throws IOException {
        Path f = prepareTestFile("simplest-zip64.jar");

        TestUtil.looksLikeGoodZip(f);
        ZipPrefixer.adjustZipOffsets(f, 0);
    }

}
