package io.ib67.sfcraft.bundler;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import lombok.SneakyThrows;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class SerialBundlerReader implements Closeable {
    protected final InputStream in;
    protected final FileChannel inChannel; // for direct copy

    protected final EntryInputStream metadataIn;
    protected final byte[] buffer = new byte[4096];
    protected boolean entryRead;
    protected int entries;

//    public SerialBundlerReader(InputStream in, InputStream metadataIn) {
//        this.in = Objects.requireNonNull(in);
//        this.metadataIn = new EntryInputStream(Objects.requireNonNull(metadataIn));
//        inChannel = null;
//    }

    public SerialBundlerReader(Path path) throws IOException {
        var raf = new RandomAccessFile(path.toFile(), "r");
        var totalLen = Files.size(path);
        var magic = raf.readInt();
        var compressed = magic == Bundle.COMPRESSED_MAGIC;
        if (!compressed && magic != Bundle.MAGIC) throw new IOException("Invalid bundle file");

        // read length
        raf.seek(totalLen - 4);
        var metadataLength = raf.readInt();
        raf.seek(totalLen - 8);
        entries = raf.readInt();
        // read metadata
        var metadataBegin = totalLen - 8 - metadataLength;
        var metadataBytes = new byte[metadataLength];
        raf.seek(metadataBegin);
        raf.readFully(metadataBytes);

        this.metadataIn = new EntryInputStream(new ZstdInputStreamNoFinalizer(new ByteArrayInputStream(metadataBytes)));
        // check compressed
        raf.seek(4);
        var fis = Channels.newInputStream(raf.getChannel());
        if (compressed) {
            in = new ZstdInputStreamNoFinalizer(fis);
            inChannel = null;
        } else {
            in = fis;
            inChannel = raf.getChannel();
        }
    }

    @SneakyThrows
    public boolean hasEntry() {
        return entries > 0;
    }

    public BundlerEntry readEntry() throws IOException {
        if (!hasEntry()) throw new IOException("All entries are read");
        var entry = metadataIn.readEntry();
        entryRead = true;
        entries--;
        return entry;
    }

    public void writeTo(FileChannel channel, BundlerEntry entry) throws IOException {
        if (inChannel != null) {
            inChannel.transferTo(entry.offset(), entry.fileLength(), channel);
        } else {
            writeTo(Channels.newOutputStream(channel), entry);
        }
    }

    public void writeTo(OutputStream out, BundlerEntry entry) throws IOException {
        Objects.requireNonNull(entry);
        var remaining = entry.fileLength();
        var buffer = this.buffer;
        var totalRead = 0;
        do {
            var read = in.read(buffer, 0, (int) Math.min(remaining - totalRead, buffer.length));
            out.write(buffer, 0, read);
            totalRead += read;
        } while (totalRead < remaining);
    }


    @Override
    public void close() throws IOException {
        in.close();
        metadataIn.close();
    }
}
