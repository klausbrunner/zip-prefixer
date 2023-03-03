package net.e175.klaus.zip;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * A small and very limited toolkit to deal with reading/writing binary files based on (fixed-size)
 * frames, particularly ZIPs. The idea is to create "patterns" (like C structs) and read these as
 * needed. Writes are collected first to be executed later as a batch.
 *
 * <p>There isn't a lot of hand-holding; bounds checking is only performed where it's cheap to do
 * and often with asserts only. Most classes here could be replaced nicely by records, cutting the
 * LOC by half or so, but we're staying at Java 8 for now.
 */
final class BinaryMapper {
  private static final Logger LOG = Logger.getLogger(BinaryMapper.class.getName());

  private BinaryMapper() {}

  static final class FieldSpec {
    final int size;
    final String name;
    final byte[] magic;

    FieldSpec(int size, String name, byte[] magic) {
      assert size > 0 && name != null;

      this.size = size;
      this.name = name;
      this.magic = magic;

      if (magic != null && magic.length != size) {
        throw new IllegalArgumentException("magic bytes size mismatch");
      }
    }

    FieldSpec(int size, String name) {
      this.size = size;
      this.name = name;
      this.magic = null;
    }

    static FieldSpec of(int size, String name) {
      return new FieldSpec(size, name);
    }

    static FieldSpec of(int size, String name, byte[] magic) {
      return new FieldSpec(size, name, magic);
    }

    @Override
    public String toString() {
      return "FieldSpec{"
          + "size="
          + size
          + ", name='"
          + name
          + '\''
          + ", magic="
          + Arrays.toString(magic)
          + '}';
    }
  }

  static final class FieldSpecInstance {
    final FieldSpec fs;
    final int position;

    FieldSpecInstance(FieldSpec fs, int position) {
      this.fs = fs;
      this.position = position;
    }

    @Override
    public String toString() {
      return "FieldSpecInstance{" + "fs=" + fs + ", position=" + position + '}';
    }
  }

  static final class PatternSpec {
    final int size;

    final ByteOrder byteOrder;

    final LinkedHashMap<String, FieldSpecInstance> nameToFSI;

    PatternSpec(ByteOrder byteOrder, FieldSpec... specFields) {
      this.nameToFSI = new LinkedHashMap<>();
      int position = 0;
      for (FieldSpec fs : specFields) {
        this.nameToFSI.put(fs.name, new FieldSpecInstance(fs, position));
        position += fs.size;
      }
      this.size = position;
      this.byteOrder = byteOrder;
    }

    ByteBuffer bufferFor() {
      ByteBuffer buf = ByteBuffer.allocate(this.size);
      buf.order(this.byteOrder);
      return buf;
    }

    @Override
    public String toString() {
      return "PatternSpec{"
          + "size="
          + size
          + ", byteOrder="
          + byteOrder
          + ", nameToFSI="
          + nameToFSI
          + '}';
    }
  }

  static final class PatternInstance {

    PatternInstance(PatternSpec spec, long position, ByteBuffer buffer) {
      this.spec = spec;
      this.position = position;
      this.buffer = buffer.duplicate();
      this.buffer.order(spec.byteOrder);
      this.buffer.rewind();
      if (this.buffer.remaining() < spec.size) {
        throw new IllegalArgumentException("buffer isn't large or filled enough to hold spec");
      }
    }

    final PatternSpec spec;

    final long position;

    final ByteBuffer buffer;

    private Write prepWrite(
        String name,
        Function<FieldSpecInstance, ByteBuffer> bufferProvider,
        Consumer<ByteBuffer> bufferFiller) {
      FieldSpecInstance fsi = locateField(name);
      ByteBuffer buf = bufferProvider.apply(fsi);
      buf.order(spec.byteOrder);
      bufferFiller.accept(buf);
      buf.flip();
      return new Write(this.position + fsi.position, buf);
    }

    Write writeAsBytes(String name, byte[] data) {
      return prepWrite(
          name,
          fsi -> {
            assert data.length <= fsi.fs.size;
            return ByteBuffer.wrap(data);
          },
          buf -> {});
    }

    private Write prepWrite(String name, Consumer<ByteBuffer> bufferFiller) {
      return prepWrite(name, fsi -> ByteBuffer.allocate(fsi.fs.size), bufferFiller);
    }

    Write writeByte(String name, byte data) {
      return prepWrite(name, buf -> buf.put(data));
    }

    Write writeShort(String name, short data) {
      return prepWrite(name, buf -> buf.putShort(data));
    }

    Write writeInt(String name, int data) {
      return prepWrite(name, buf -> buf.putInt(data));
    }

    Write writeLong(String name, long data) {
      return prepWrite(name, buf -> buf.putLong(data));
    }

    byte getByte(String name) {
      FieldSpecInstance fsi = locateField(name);
      return buffer.get(fsi.position);
    }

    byte[] getBytes(String name) {
      FieldSpecInstance fsi = locateField(name);
      return getBytes(fsi);
    }

    byte[] getBytes(FieldSpecInstance fsi) {
      byte[] result = new byte[fsi.fs.size];
      buffer.position(fsi.position);
      buffer.get(result, 0, result.length);
      return result;
    }

    short getShort(String name) {
      FieldSpecInstance fsi = locateField(name);
      assert fsi.fs.size >= 2;
      return buffer.getShort(fsi.position);
    }

    int getUnsignedShort(String name) {
      return Short.toUnsignedInt(getShort(name));
    }

    int getInt(String name) {
      FieldSpecInstance fsi = locateField(name);
      assert fsi.fs.size >= 4;
      return buffer.getInt(fsi.position);
    }

    long getUnsignedInt(String name) {
      return Integer.toUnsignedLong(getInt(name));
    }

    long getLong(String name) {
      FieldSpecInstance fsi = locateField(name);
      assert fsi.fs.size >= 8;
      return buffer.getLong(fsi.position);
    }

    boolean validateMagic() {
      for (FieldSpecInstance fsi : spec.nameToFSI.values()) {
        if (fsi.fs.magic != null && !Arrays.equals(fsi.fs.magic, getBytes(fsi))) {
          return false;
        }
      }
      return true;
    }

    private FieldSpecInstance locateField(String name) {
      FieldSpecInstance fsi = spec.nameToFSI.get(name);
      if (fsi == null) {
        throw new IllegalArgumentException("no such field in my PatternSpec");
      }
      return fsi;
    }

    @Override
    public String toString() {
      return "PatternInstance{"
          + "spec="
          + spec
          + ", position="
          + position
          + ", buffer="
          + buffer
          + '}';
    }
  }

  static final class Write {
    final long position;
    final ByteBuffer data;

    Write(long position, ByteBuffer data) {
      this.position = position;
      this.data = data;
    }

    @Override
    public String toString() {
      return "Write{" + "position=" + position + ", data=" + data + '}';
    }
  }

  /**
   * Seek for a pattern in the given channel, starting from a given position. If the PatternSpec has
   * any magic defined, this is used to test if the pattern has been found. If not, move forward or
   * backward one position and try again until found or limits reached.
   */
  static Optional<PatternInstance> seek(
      PatternSpec spec, SeekableByteChannel inChannel, long startPosition, boolean forward)
      throws IOException {
    return seek(spec, inChannel, startPosition, -1, forward);
  }

  /**
   * Seek for a pattern in the given channel, starting from a given position. If the PatternSpec has
   * any magic defined, this is used to test if the pattern has been found. If not, move forward or
   * backward one position and try again until found or either limits or given maxDistance of steps
   * reached.
   */
  static Optional<PatternInstance> seek(
      PatternSpec spec,
      SeekableByteChannel inChannel,
      long startPosition,
      long maxDistance,
      boolean forward)
      throws IOException {
    final long maxPosition = inChannel.size() - spec.size;

    final BooleanSupplier mayProceed;
    final AtomicLong stepCounter;
    if (maxDistance > 0) {
      stepCounter = new AtomicLong();
      mayProceed = () -> stepCounter.incrementAndGet() <= maxDistance;
    } else {
      mayProceed = () -> true;
    }

    final long step = forward ? 1L : -1L;

    return seek(
        spec,
        inChannel,
        forward ? Math.max(0, startPosition) : Math.min(maxPosition, startPosition),
        pi -> mayProceed.getAsBoolean() ? step : 0,
        0,
        maxPosition);
  }

  /**
   * Seek for a pattern in the given channel, starting from a given position. If the PatternSpec has
   * any magic defined, this is used to test if the pattern has been found. If not, move forward or
   * backward by the amount supplied by stepSupplier and try again. If that amount is 0, end the
   * search.
   */
  static Optional<PatternInstance> seek(
      PatternSpec spec,
      SeekableByteChannel inChannel,
      long startPosition,
      Function<PatternInstance, Long> stepSupplier,
      long minPosition,
      long maxPosition)
      throws IOException {
    long stepSupplied;
    final ByteBuffer buf = spec.bufferFor();

    final long realMaxPosition = Math.min(maxPosition, inChannel.size() - spec.size);
    final long realMinPosition = Math.max(0, minPosition);

    for (long i = startPosition; i <= realMaxPosition && i >= realMinPosition; i += stepSupplied) {

      PatternInstance readInstance = readUnvalidated(spec, inChannel, i, buf);

      if (readInstance.validateMagic()) {
        return Optional.of(readInstance);
      }

      stepSupplied = stepSupplier.apply(readInstance);

      if (stepSupplied == 0) {
        break;
      }
    }
    return Optional.empty();
  }

  /**
   * Read bytes at given location, assuming that the given PatternSpec is found there. If this has
   * any magic defined, it's used to validate. This reuses a supplied ByteBuffer.
   */
  static Optional<PatternInstance> read(
      PatternSpec spec, SeekableByteChannel inChannel, long position, ByteBuffer buf)
      throws IOException {
    PatternInstance pi = readUnvalidated(spec, inChannel, position, buf);
    if (pi.validateMagic()) {
      return Optional.of(pi);
    }
    return Optional.empty();
  }

  /**
   * Read bytes at given location, assuming that the given PatternSpec is found there. Magic is not
   * validated. This reuses a supplied ByteBuffer.
   */
  static PatternInstance readUnvalidated(
      PatternSpec spec, SeekableByteChannel inChannel, long position, ByteBuffer buf)
      throws IOException {
    inChannel.position(position);
    int bytesRead = inChannel.read(buf);
    assert bytesRead == spec.size;
    PatternInstance pi = new PatternInstance(spec, position, buf);
    buf.rewind();
    return pi;
  }

  /**
   * Read bytes at given location, assuming that the given PatternSpec is found there. If this has
   * any magic defined, it's used to validate. This allocates a new ByteBuffer.
   */
  static Optional<PatternInstance> read(PatternSpec spec, SeekableByteChannel inChannel, long i)
      throws IOException {
    ByteBuffer buf = spec.bufferFor();
    return read(spec, inChannel, i, buf);
  }

  /** Create a new queue for Writes that's ordered by position. */
  static Queue<Write> createWriteQueue() {
    return new PriorityQueue<>(11, Comparator.comparingLong(w -> w.position));
  }

  /** Sequentially perform all the writes in the given queue on the given channel. */
  static void applyWrites(Queue<Write> writes, SeekableByteChannel toChannel) throws IOException {
    LOG.fine(() -> "writing " + writes.size() + " Writes");
    while (!writes.isEmpty()) {
      Write w = writes.poll();
      toChannel.position(w.position);
      toChannel.write(w.data);
      LOG.fine(() -> "wrote " + w);
    }
  }
}
