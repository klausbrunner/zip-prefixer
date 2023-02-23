package net.e175.klaus.zip;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An instrumented pass-through SeekableByteChannel.
 */
final class CountingSeekableByteChannel implements SeekableByteChannel {
    private final SeekableByteChannel basedOn;

    final AtomicLong readCount = new AtomicLong();
    final AtomicLong readByteCount = new AtomicLong();
    final AtomicLong writeCount = new AtomicLong();
    final AtomicLong writeByteCount = new AtomicLong();
    final AtomicLong positionCount = new AtomicLong();

    CountingSeekableByteChannel(SeekableByteChannel basedOn) {
        this.basedOn = basedOn;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        final int result = basedOn.read(dst);
        readCount.incrementAndGet();
        readByteCount.getAndAdd(result);
        return result;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        final int result = basedOn.write(src);
        writeCount.incrementAndGet();
        writeByteCount.getAndAdd(result);
        return result;
    }

    @Override
    public long position() throws IOException {
        return basedOn.position();
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        positionCount.incrementAndGet();
        return basedOn.position(newPosition);
    }

    @Override
    public long size() throws IOException {
        return basedOn.position();
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        return basedOn.truncate(size);
    }

    @Override
    public boolean isOpen() {
        return basedOn.isOpen();
    }

    @Override
    public void close() throws IOException {
        basedOn.close();
    }
}
