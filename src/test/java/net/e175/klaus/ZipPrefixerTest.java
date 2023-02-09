package net.e175.klaus;

import org.junit.jupiter.api.Test;

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

        ZipPrefixer.looksLikeGoodZip(f);
        ZipPrefixer.adjustZipOffsets(f, 0);
    }

    @Test
    void adjustsZipOffsets() throws IOException {
        Path f = prepareTestFile("simplest.jar");

        final byte[] prefix = "0123456789".getBytes(StandardCharsets.UTF_8);

        assertEquals(prefix.length, ZipPrefixer.applyPrefixes(f, prefix));

        try {
            ZipPrefixer.validateZipOffsets(f);
            fail("should have thrown an exception, but didn't");
        } catch (IOException ignored) {
        }

        ZipPrefixer.adjustZipOffsets(f, prefix.length);

        ZipPrefixer.validateZipOffsets(f);
        ZipPrefixer.looksLikeGoodZip(f);
    }

}
