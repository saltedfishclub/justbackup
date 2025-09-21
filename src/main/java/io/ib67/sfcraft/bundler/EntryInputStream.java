package io.ib67.sfcraft.bundler;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class EntryInputStream extends FilterInputStream {
    /**
     * Creates a {@code FilterInputStream}
     * by assigning the  argument {@code in}
     * to the field {@code this.in} so as
     * to remember it for later use.
     *
     * @param in the underlying input stream, or {@code null} if
     *           this instance is to be created without an underlying stream.
     */
    protected EntryInputStream(InputStream in) {
        super(in);
    }

    public BundlerEntry readEntry() throws IOException {
        var len = ByteBuffer.wrap(readNBytes(4)).getInt();
        var gunzipped = len < 0;
        len = Math.abs(len);
        var name = new String(readNBytes(len)).intern();
        var buf = ByteBuffer.wrap(readNBytes(8));
        var fileLen = buf.getLong();
        var fileOffset = buf.getLong();
        return new BundlerEntry(name, gunzipped, fileLen, fileOffset);

    }
}
