package io.ib67.sfcraft.bundler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class EntryOutputStream extends FilterOutputStream {
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
    }

    public void writeEntryHeader(String path, short attribute, long segmentLen, long time) throws IOException {
        var name = path.getBytes(StandardCharsets.UTF_8);
        out.write(BundleEntry.MAGIC >> 8);
        out.write(BundleEntry.MAGIC & 0xFF); // u16 be
        var timeArr = new byte[] { // u64 be
                (byte) (time >> 56),
                (byte) (time >> 48),
                (byte) (time >> 40),
                (byte) (time >> 32),
                (byte) (time >> 24),
                (byte) (time >> 16),
                (byte) (time >> 8),
                (byte) time
        };
        out.write(timeArr);
        VarInts.writeVarInt(out, attribute);
        VarInts.writeVarLong(out, segmentLen);
        VarInts.writeVarInt(out, name.length);
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
