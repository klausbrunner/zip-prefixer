package net.e175.klaus.zip;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Queue;

import static net.e175.klaus.zip.BinaryMapper.*;
import static net.e175.klaus.zip.TestUtil.prepareTestFile;
import static org.junit.jupiter.api.Assertions.*;

class BinaryMapperTest {

    public static final PatternSpec TEST_SPEC_1 = new PatternSpec(
            ByteOrder.LITTLE_ENDIAN,
            FieldSpec.of(5, "magic1", "magic".getBytes(StandardCharsets.US_ASCII)),
            FieldSpec.of(2, "short1"),
            FieldSpec.of(1, "byte1")
    );

    public static final PatternSpec TEST_SPEC_UNSIGNED_LE = new PatternSpec(
            ByteOrder.LITTLE_ENDIAN,
            FieldSpec.of(4, "unsignedInt1"),
            FieldSpec.of(2, "unsignedShort1")
    );

    public static final PatternSpec TEST_SPEC_UNSIGNED_BE = new PatternSpec(
            ByteOrder.BIG_ENDIAN,
            FieldSpec.of(4, "unsignedInt1"),
            FieldSpec.of(2, "unsignedShort1")
    );

    @Test
    void testBasics() throws IOException {
        Path f = prepareTestFile("test-1.bin");

        try (SeekableByteChannel channel = Files.newByteChannel(f)) {
            assertNotNull(channel);
            assertEquals(19, channel.size());

            ByteBuffer buf = TEST_SPEC_1.bufferFor();
            channel.position(4);
            channel.read(buf);
            PatternInstance pi = new PatternInstance(TEST_SPEC_1, channel.position(), buf);

            assertEquals("magic", new String(pi.getBytes("magic1"), StandardCharsets.UTF_8));
            assertTrue(pi.validateMagic());
            assertEquals(1234, pi.getShort("short1"));
            assertEquals(123, pi.getByte("byte1"));
        }
    }

    @Test
    void testBasics2() throws IOException {
        Path f = prepareTestFile("test-1.bin");

        try (SeekableByteChannel channel = Files.newByteChannel(f)) {
            PatternInstance pi = BinaryMapper.read(TEST_SPEC_1, channel, 4).orElseThrow(RuntimeException::new);

            assertEquals("magic", new String(pi.getBytes("magic1"), StandardCharsets.UTF_8));
            assertEquals(1234, pi.getShort("short1"));
            assertEquals(123, pi.getByte("byte1"));

            BinaryMapper.read(TEST_SPEC_1, channel, 6).ifPresent(i -> {
                throw new RuntimeException("this shouldn't have worked");
            });
        }
    }

    @Test
    void testSeek() throws IOException {
        Path f = prepareTestFile("test-1.bin");

        try (SeekableByteChannel channel = Files.newByteChannel(f)) {
            Optional<PatternInstance> result = BinaryMapper.seek(TEST_SPEC_1, channel, 0, true);
            assertTrue(result.isPresent());
            result.ifPresent(pi -> assertEquals(4, pi.position));

            result = BinaryMapper.seek(TEST_SPEC_1, channel, 5, true);
            assertFalse(result.isPresent());

            result = BinaryMapper.seek(TEST_SPEC_1, channel, 5, false);
            assertTrue(result.isPresent());
            result.ifPresent(pi -> assertEquals(4, pi.position));

            result = BinaryMapper.seek(TEST_SPEC_1, channel, Long.MAX_VALUE, false);
            assertTrue(result.isPresent());
            result.ifPresent(pi -> assertEquals(4, pi.position));
        }
    }

    @Test
    void testSeek2() throws IOException {
        Path f = prepareTestFile("test-2.bin");

        try (SeekableByteChannel channel = Files.newByteChannel(f)) {
            Optional<PatternInstance> result = BinaryMapper.seek(TEST_SPEC_1, channel, 0, true);
            assertTrue(result.isPresent());
            result.ifPresent(pi -> assertEquals(4, pi.position));


            result = BinaryMapper.seek(TEST_SPEC_1, channel, Long.MAX_VALUE, false);
            assertTrue(result.isPresent());
            result.ifPresent(pi -> assertEquals(4, pi.position));
        }
    }

    @Test
    void testBasicWrite() throws IOException {
        Path f = prepareTestFile("test-1.bin");

        try (SeekableByteChannel channel = Files.newByteChannel(f, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            PatternInstance pi = BinaryMapper.read(TEST_SPEC_1, channel, 4).orElseThrow(RuntimeException::new);

            Write w = pi.writeShort("short1", (short) 4321);

            channel.position(w.position);
            channel.write(w.data);
        }

        try (SeekableByteChannel channel = Files.newByteChannel(f, StandardOpenOption.READ)) {
            PatternInstance pi = BinaryMapper.read(TEST_SPEC_1, channel, 4).orElseThrow(RuntimeException::new);
            assertEquals(4321, pi.getShort("short1"));
        }
    }

    @Test
    void testMultiWrite() throws IOException {
        Path f = prepareTestFile("test-1.bin");

        try (SeekableByteChannel channel = Files.newByteChannel(f, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            PatternInstance pi = BinaryMapper.read(TEST_SPEC_1, channel, 4).orElseThrow(RuntimeException::new);

            Queue<Write> writeQueue = createWriteQueue();

            writeQueue.add(pi.writeShort("short1", (short) 4321));
            writeQueue.add(pi.writeByte("byte1", (byte) 32));

            applyWrites(writeQueue, channel);
        }

        try (SeekableByteChannel channel = Files.newByteChannel(f, StandardOpenOption.READ)) {
            PatternInstance pi = BinaryMapper.read(TEST_SPEC_1, channel, 4).orElseThrow(RuntimeException::new);
            assertEquals(4321, pi.getShort("short1"));
            assertEquals(32, pi.getByte("byte1"));
        }
    }

    @Test
    void testUnsignedBEread() throws IOException {
        Path f = prepareTestFile("test-unsigned-be.bin");
        try (SeekableByteChannel channel = Files.newByteChannel(f, StandardOpenOption.READ)) {
            PatternInstance pi = BinaryMapper.read(TEST_SPEC_UNSIGNED_BE, channel, 0).orElseThrow(RuntimeException::new);
            assertEquals(3_333_333_333L, pi.getUnsignedInt("unsignedInt1"));
            assertEquals(55_555, pi.getUnsignedShort("unsignedShort1"));
        }
    }

    @Test
    void testUnsignedLEread() throws IOException {
        Path f = prepareTestFile("test-unsigned-le.bin");
        try (SeekableByteChannel channel = Files.newByteChannel(f, StandardOpenOption.READ)) {
            PatternInstance pi = BinaryMapper.read(TEST_SPEC_UNSIGNED_LE, channel, 0).orElseThrow(RuntimeException::new);
            assertEquals(3_333_333_333L, pi.getUnsignedInt("unsignedInt1"));
            assertEquals(55_555, pi.getUnsignedShort("unsignedShort1"));
        }
    }

    @Test
    void testUnsignedBEwrite() throws IOException {
        Path f = prepareTestFile("test-unsigned-be.bin");

        try (SeekableByteChannel channel = Files.newByteChannel(f, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            PatternInstance pi = BinaryMapper.read(TEST_SPEC_UNSIGNED_BE, channel, 0).orElseThrow(RuntimeException::new);
            assertEquals(3_333_333_333L, pi.getUnsignedInt("unsignedInt1"));
            assertEquals(55_555, pi.getUnsignedShort("unsignedShort1"));

            Queue<Write> writeQueue = createWriteQueue();
            writeQueue.add(pi.writeInt("unsignedInt1", (int) 3_333_333_334L));
            writeQueue.add(pi.writeShort("unsignedShort1", (short) 55_556));
            applyWrites(writeQueue, channel);

            PatternInstance pi2 = BinaryMapper.read(TEST_SPEC_UNSIGNED_BE, channel, 0).orElseThrow(RuntimeException::new);
            assertEquals(3_333_333_334L, pi2.getUnsignedInt("unsignedInt1"));
            assertEquals(55_556, pi2.getUnsignedShort("unsignedShort1"));
        }
    }

    @Test
    void testUnsignedLEwrite() throws IOException {
        Path f = prepareTestFile("test-unsigned-le.bin");

        try (SeekableByteChannel channel = Files.newByteChannel(f, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            PatternInstance pi = BinaryMapper.read(TEST_SPEC_UNSIGNED_LE, channel, 0).orElseThrow(RuntimeException::new);
            assertEquals(3_333_333_333L, pi.getUnsignedInt("unsignedInt1"));
            assertEquals(55_555, pi.getUnsignedShort("unsignedShort1"));

            Queue<Write> writeQueue = createWriteQueue();
            writeQueue.add(pi.writeInt("unsignedInt1", (int) 3_333_333_334L));
            writeQueue.add(pi.writeShort("unsignedShort1", (short) 55_556));
            applyWrites(writeQueue, channel);

            PatternInstance pi2 = BinaryMapper.read(TEST_SPEC_UNSIGNED_LE, channel, 0).orElseThrow(RuntimeException::new);
            assertEquals(3_333_333_334L, pi2.getUnsignedInt("unsignedInt1"));
            assertEquals(55_556, pi2.getUnsignedShort("unsignedShort1"));
        }
    }


}
