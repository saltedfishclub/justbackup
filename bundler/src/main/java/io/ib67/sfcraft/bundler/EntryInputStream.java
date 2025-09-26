package io.ib67.sfcraft.bundler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class EntryInputStream extends FilterInputStream {
    private final ByteBuffer buffer;

    /**
     * Creates a {@code FilterInputStream}
     * by assigning the  argument {@code in}
     * to the field {@code this.in}
     * to remember it for later use.
     *
     * @param in the underlying input stream, or {@code null} if
     *           this instance is to be created without an underlying stream.
     */
    protected EntryInputStream(InputStream in) {
        super(in);
        this.buffer = ByteBuffer.allocate(4 + 8 + 4);
    }

    public BundleEntry readEntry() throws IOException {
        buffer.clear();
        in.read(buffer.array(), 0, 4+8+4);
        var magic = buffer.getShort();
        if (magic != BundleEntry.MAGIC) throw new IOException("Invalid magic number");
        var attribute = buffer.getShort();
        var fileLen = buffer.getLong();
        var lenName = buffer.getInt();
        var name = new String(in.readNBytes(lenName)).intern();
        return new BundleEntry(attribute, fileLen, name);
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}
