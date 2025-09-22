package io.ib67.sfcraft.bundler;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

@AllArgsConstructor
public class SerialBundlerWriter implements Closeable {
    protected final FileChannel channel;
    protected final OutputStream out;
    protected long offset;
    @Getter
    protected List<BundlerEntry> entries;

    public SerialBundlerWriter(OutputStream out) {
        Objects.requireNonNull(out);
        this.out = out;
        this.entries = new ArrayList<>();
        channel = null;
    }

    public SerialBundlerWriter(FileChannel channel, long offset) {
        Objects.requireNonNull(channel);
        this.offset = offset;
        this.entries = new ArrayList<>();
        this.channel = channel;
        out = null;
    }

    public void writeGunzip(Path path, String fileName) throws IOException {
        try (var gzip = new GZIPInputStream(Files.newInputStream(path, StandardOpenOption.READ))) {
            var len = channel != null ? gzip.transferTo(Channels.newOutputStream(channel)) :
                    gzip.transferTo(out);
            entries.add(new BundlerEntry(fileName, true, len, offset));
            offset += len;
        }
    }

    public void writePlain(Path path, String fileName) throws IOException {
        if (channel != null) {
            writeZC(path, fileName);
        } else {
            var current = offset;
            try (var in = Files.newInputStream(path)) {
                in.transferTo(out);
            }
            entries.add(new BundlerEntry(fileName, false, Files.size(path), current));
        }
    }

    private void writeZC(Path path, String fileName) throws IOException {
        try (var in = FileChannel.open(path, StandardOpenOption.READ)) {
            var begin = offset;
            var currentPosition = offset;
            long transferred = 0;
            while ((transferred = in.transferTo(currentPosition, in.size() - transferred, channel)) != 0) {
                currentPosition += transferred;
            }
            offset = currentPosition;
            entries.add(new BundlerEntry(
                    fileName, false, offset - begin, begin
            ));
        }
    }

    @Override
    public void close() throws IOException {
        if (out != null) out.close();
        if (channel != null) channel.close();
    }
}
