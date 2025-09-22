package io.ib67.sfcraft.bundler;

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

    public void write(BundlerEntry entry) throws IOException {
        var fileName = entry.fileName().getBytes(StandardCharsets.UTF_8);
        var buf = ByteBuffer.allocate(4).putInt(entry.unGzipped() ? fileName.length * -1 : fileName.length);
        out.write(buf.array());
        out.write(fileName);
        buf = ByteBuffer.allocate(8 + 8);
        buf.putLong(entry.fileLength());
        buf.putLong(entry.offset());
        out.write(buf.array());
    }
}
