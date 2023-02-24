package net.e175.klaus.zip;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipException;

import static net.e175.klaus.zip.TestUtil.prepareTestFile;
import static net.e175.klaus.zip.ZipPrefixer.*;
import static org.junit.jupiter.api.Assertions.*;

class ZipPrefixerTest {

    @Test
    void appliesPrefixes() throws IOException {
        Path f = prepareTestFile("bla.txt");

        long prefixLength = applyPrefixes(f,
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

        long prefixLength = applyPrefixes(f, Collections.singletonList(f2));

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
        applyPrefixes(f, "broken".getBytes(StandardCharsets.UTF_8));
        try {
            validateZipOffsets(f);
            fail("should have thrown an exception");
        } catch (IOException ignored) {
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simplest.jar", "simplest-zip64.jar", "single-1g-file.zip", "single-10g-file.zip",
            "2k-tiny-files.zip", "20k-tiny-files.zip", "small-forced-zip64.zip", "small-forced-zip64-python.zip",
            "zip64-golang.zip", "winzip-normal.zip", "winzip-zipx.zip"})
    void adjustsZipOffsets(String filename) throws IOException {
        Path f = prepareTestFile(filename);

        TestUtil.looksLikeGoodZip(f);

        final byte[] prefix = "0123456789".getBytes(StandardCharsets.UTF_8);

        applyPrefixesToZip(f, prefix);

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

        assertEquals(prefix.length, applyPrefixes(f, prefix));

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
                () -> applyPrefixesToZip(zip, Arrays.asList(filler, filler, filler, filler)));
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
        assertThrows(
                ZipException.class,
                () -> ZipPrefixer.looksLikeZip(f2));
    }

}
