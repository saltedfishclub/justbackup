package io.ib67.sfcraft.bundler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class EntryInputStream extends FilterInputStream {
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
    }

    public BundleEntry readEntry() throws IOException {
        var magic = (short) (in.read() << 8 | in.read()); // u16
        if (magic != BundleEntry.MAGIC) throw new IOException("Invalid magic number: "+Long.toHexString(magic));
        var time = (long) in.read() << 56 | (long) in.read() << 48 | (long) in.read() << 40 | (long) in.read() << 32 |
                (long) in.read() << 24 | in.read() << 16 | in.read() << 8 | in.read();
        var attribute = VarInts.readVarInt(in);
        var segmentLen = VarInts.readVarLong(in);
        var lenName = VarInts.readVarInt(in);
        var name = new String(in.readNBytes(lenName)).intern();
        return new BundleEntry((short) attribute, time, segmentLen, name);
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}
