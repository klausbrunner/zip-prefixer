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
 * A toolkit for reading/writing binary files based on fixed-size frames, particularly ZIPs.
 * Patterns (like C structs) can be defined and read as needed. Writes are collected for batch
 * execution.
 */
final class BinaryMapper {
  private static final Logger LOG = Logger.getLogger(BinaryMapper.class.getName());

  private BinaryMapper() {}

  public record FieldSpec(int size, String name, byte[] magic) {
    public FieldSpec {
      Objects.requireNonNull(name);
      if (size <= 0) throw new IllegalArgumentException("size must be > 0");
      if (magic != null) {
        if (magic.length != size) {
          throw new IllegalArgumentException("magic bytes size mismatch");
        }
        magic = magic.clone();
      }
    }

    @Override
    public byte[] magic() {
      return magic != null ? magic.clone() : null;
    }

    public static FieldSpec of(int size, String name) {
      return new FieldSpec(size, name, null);
    }

    public static FieldSpec of(int size, String name, byte[] magic) {
      return new FieldSpec(size, name, magic);
    }
  }

  public record FieldSpecInstance(FieldSpec fs, int position) {}

  public record PatternSpec(
      int size, ByteOrder byteOrder, Map<String, FieldSpecInstance> nameToFSI) {
    public PatternSpec {
      Objects.requireNonNull(byteOrder);
      Objects.requireNonNull(nameToFSI);
      nameToFSI = Collections.unmodifiableMap(new LinkedHashMap<>(nameToFSI));
    }

    public PatternSpec(ByteOrder byteOrder, FieldSpec... specFields) {
      this(calculateSize(specFields), byteOrder, createImmutableMap(specFields));
    }

    private static Map<String, FieldSpecInstance> createImmutableMap(FieldSpec... specFields) {
      var map = new LinkedHashMap<String, FieldSpecInstance>();
      var position = 0;
      for (var fs : specFields) {
        map.put(fs.name(), new FieldSpecInstance(fs, position));
        position += fs.size();
      }
      return Collections.unmodifiableMap(map);
    }

    private static int calculateSize(FieldSpec... specFields) {
      return Arrays.stream(specFields).mapToInt(FieldSpec::size).sum();
    }

    ByteBuffer bufferFor() {
      return ByteBuffer.allocate(size).order(byteOrder);
    }
  }

  public record PatternInstance(PatternSpec spec, long position, ByteBuffer buffer) {
    public PatternInstance {
      Objects.requireNonNull(spec);
      Objects.requireNonNull(buffer);
      buffer = buffer.asReadOnlyBuffer().order(spec.byteOrder()).rewind();
      if (buffer.remaining() < spec.size()) {
        throw new IllegalArgumentException("buffer isn't large or filled enough to hold spec");
      }
    }

    private Write prepWrite(
        String name,
        Function<FieldSpecInstance, ByteBuffer> bufferProvider,
        Consumer<ByteBuffer> bufferFiller) {
      var fsi = locateField(name);
      var buf = bufferProvider.apply(fsi).order(spec.byteOrder());
      bufferFiller.accept(buf);
      buf.flip();
      return new Write(position + fsi.position(), buf);
    }

    Write writeAsBytes(String name, byte[] data) {
      var fsi = locateField(name);
      if (data.length > fsi.fs().size()) {
        throw new IllegalArgumentException("data length exceeds field size");
      }
      return prepWrite(name, ignored -> ByteBuffer.wrap(data), buf -> {});
    }

    Write writeByte(String name, byte data) {
      return prepWrite(name, ignored -> ByteBuffer.allocate(1), buf -> buf.put(data));
    }

    Write writeShort(String name, short data) {
      return prepWrite(name, ignored -> ByteBuffer.allocate(2), buf -> buf.putShort(data));
    }

    Write writeInt(String name, int data) {
      return prepWrite(name, ignored -> ByteBuffer.allocate(4), buf -> buf.putInt(data));
    }

    Write writeLong(String name, long data) {
      return prepWrite(name, ignored -> ByteBuffer.allocate(8), buf -> buf.putLong(data));
    }

    byte getByte(String name) {
      return buffer.get(locateField(name).position());
    }

    byte[] getBytes(String name) {
      return getBytes(locateField(name));
    }

    byte[] getBytes(FieldSpecInstance fsi) {
      var result = new byte[fsi.fs().size()];
      buffer.position(fsi.position());
      buffer.get(result);
      return result;
    }

    short getShort(String name) {
      var fsi = locateField(name);
      assert fsi.fs().size() >= 2;
      return buffer.getShort(fsi.position());
    }

    int getUnsignedShort(String name) {
      return Short.toUnsignedInt(getShort(name));
    }

    int getInt(String name) {
      var fsi = locateField(name);
      assert fsi.fs().size() >= 4;
      return buffer.getInt(fsi.position());
    }

    long getUnsignedInt(String name) {
      return Integer.toUnsignedLong(getInt(name));
    }

    long getLong(String name) {
      var fsi = locateField(name);
      assert fsi.fs().size() >= 8;
      return buffer.getLong(fsi.position());
    }

    boolean validateMagic() {
      return spec.nameToFSI().values().stream()
          .allMatch(
              fsi -> fsi.fs().magic() == null || Arrays.equals(fsi.fs().magic(), getBytes(fsi)));
    }

    private FieldSpecInstance locateField(String name) {
      var fsi = spec.nameToFSI().get(name);
      if (fsi == null) {
        throw new IllegalArgumentException("no such field in my PatternSpec");
      }
      return fsi;
    }
  }

  public record Write(long position, ByteBuffer data) {
    public Write {
      Objects.requireNonNull(data);
      data = data.asReadOnlyBuffer();
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
    final long maxPosition = inChannel.size() - spec.size();
    final long step = forward ? 1L : -1L;
    final AtomicLong stepCounter = new AtomicLong();
    final BooleanSupplier mayProceed =
        maxDistance > 0 ? () -> stepCounter.incrementAndGet() <= maxDistance : () -> true;

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
    final var buf = spec.bufferFor();
    final long realMaxPosition = Math.min(maxPosition, inChannel.size() - spec.size());
    final long realMinPosition = Math.max(0, minPosition);

    for (long i = startPosition; i <= realMaxPosition && i >= realMinPosition; ) {
      var readInstance = readUnvalidated(spec, inChannel, i, buf);
      if (readInstance.validateMagic()) {
        return Optional.of(readInstance);
      }
      buf.rewind();
      long step = stepSupplier.apply(readInstance);
      if (step == 0) {
        break;
      }
      i += step;
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
    var pi = readUnvalidated(spec, inChannel, position, buf);
    return pi.validateMagic() ? Optional.of(pi) : Optional.empty();
  }

  /**
   * Read bytes at given location, assuming that the given PatternSpec is found there. Magic is not
   * validated. This reuses a supplied ByteBuffer.
   */
  static PatternInstance readUnvalidated(
      PatternSpec spec, SeekableByteChannel inChannel, long position, ByteBuffer buf)
      throws IOException {
    inChannel.position(position);
    buf.clear();
    int bytesRead = inChannel.read(buf);
    assert bytesRead == spec.size;
    var pi = new PatternInstance(spec, position, buf);
    buf.rewind();
    return pi;
  }

  /**
   * Read bytes at given location, assuming that the given PatternSpec is found there. If this has
   * any magic defined, it's used to validate. This allocates a new ByteBuffer.
   */
  static Optional<PatternInstance> read(PatternSpec spec, SeekableByteChannel inChannel, long i)
      throws IOException {
    return read(spec, inChannel, i, spec.bufferFor());
  }

  /** Create a new queue for Writes that's ordered by position. */
  static Queue<Write> createWriteQueue() {
    return new PriorityQueue<>(11, Comparator.comparingLong(Write::position));
  }

  /** Sequentially perform all the writes in the given queue on the given channel. */
  static void applyWrites(Queue<Write> writes, SeekableByteChannel toChannel) throws IOException {
    LOG.fine(() -> "writing " + writes.size() + " Writes");
    while (!writes.isEmpty()) {
      var w = writes.poll();
      toChannel.position(w.position());
      toChannel.write(w.data());
      LOG.fine(() -> "wrote " + w);
    }
  }
}
