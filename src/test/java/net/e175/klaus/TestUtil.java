package net.e175.klaus;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

final class TestUtil {
    private TestUtil() {
    }

    private static final ClassLoader cl = TestUtil.class.getClassLoader();

    static Path prepareTestFile(String filename) throws IOException {
        try (InputStream is = cl.getResourceAsStream(filename)) {
            assert is != null;
            Path target = Files.createTempFile("test", ".jar");
            Files.copy(is, target, REPLACE_EXISTING);
            File result = target.toFile();
            result.deleteOnExit();
            return result.toPath();
        }
    }
}
