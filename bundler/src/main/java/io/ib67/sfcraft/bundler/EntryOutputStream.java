package io.ib67.sfcraft.bundler;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class EntryOutputStream extends FilterOutputStream {
    protected final ByteBuffer buffer;
    /**
     * Creates an output stream filter built on top of the specified
     * underlying output stream.
     *
     * @param out the underlying output stream to be assigned to
     *            the field {@code this.out} for later use, or
     *            {@code null} if this instance is to be
     *            created without an underlying stream.
     */
    public EntryOutputStream(OutputStream out) {
        super(out);
        this.buffer = ByteBuffer.allocate(4+8+4);
    }

    public void writeEntryHeader(String path, short attribute, long fileLen) throws IOException {
        var name = path.getBytes(StandardCharsets.UTF_8);
        buffer.clear();
        buffer.putShort(BundleEntry.MAGIC);
        buffer.putShort(attribute);
        buffer.putLong(fileLen);
        buffer.putInt(name.length);
        out.write(buffer.array());
        out.write(name);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
    }

    @Override
    public void write(byte[] b) throws IOException {
        out.write(b);
    }
}
