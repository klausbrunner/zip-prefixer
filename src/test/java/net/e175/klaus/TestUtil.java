package net.e175.klaus;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

final class TestUtil {
    private TestUtil() {
    }

    private static final ClassLoader cl = TestUtil.class.getClassLoader();

    static Path prepareTestFile(String filename) throws IOException {
        try (GZIPInputStream gzi =
                     new GZIPInputStream(Objects.requireNonNull(cl.getResourceAsStream(filename + ".gz")))) {
            Path target = Files.createTempFile("test", ".zip");
            Files.copy(gzi, target, REPLACE_EXISTING);
            System.out.printf("%s -> %s%n", filename, target);
            target.toFile().deleteOnExit();
            return target;
        }
    }


    /**
     * Test if this looks like a good zip file, with proper offsets and retrievable files.
     */
    static Path looksLikeGoodZip(Path f) throws IOException {
        try (ZipFile archive = new ZipFile(f)) {
            Enumeration<ZipArchiveEntry> entries = archive.getEntriesInPhysicalOrder();
            while(entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if(!archive.canReadEntryData(entry)) {
                    throw new ZipException("can't read entry " + entry);
                }
            }
        }
        return f;
    }
}
