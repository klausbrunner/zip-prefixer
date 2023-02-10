package net.e175.klaus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static net.e175.klaus.TestUtil.prepareTestFile;
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
    void validatesZipOffsets() throws IOException {
        Path f = prepareTestFile("simplest.jar");

        TestUtil.looksLikeGoodZip(f);
        ZipPrefixer.adjustZipOffsets(f, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"simplest.jar", "simplest-zip64.jar", "single-1g-file.zip", "single-10g-file.zip", "2k-tiny-files.zip", "20k-tiny-files.zip"})
    void adjustsZipOffsets(String filename) throws IOException {
        Path f = prepareTestFile(filename);

        TestUtil.looksLikeGoodZip(f);

        final byte[] prefix = "0123456789".getBytes(StandardCharsets.UTF_8);

        assertEquals(prefix.length, ZipPrefixer.applyPrefixes(f, prefix));

        try {
            ZipPrefixer.validateZipOffsets(f);
            fail("should have thrown an exception, but didn't");
        } catch (IOException ignored) {
        }

        ZipPrefixer.adjustZipOffsets(f, prefix.length);

        ZipPrefixer.validateZipOffsets(f);
        TestUtil.looksLikeGoodZip(f);
    }

    @Test
    void validatesZipOffsets64() throws IOException {
        Path f = prepareTestFile("simplest-zip64.jar");

        TestUtil.looksLikeGoodZip(f);
        ZipPrefixer.adjustZipOffsets(f, 0);
    }

}
