package io.ib67.sfcraft.bundler;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStreamNoFinalizer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class BundleReader {
    public static void extract(Path bundlePath, Path destination) throws IOException {
        var buffer = new byte[4096];
        try (var in = new EntryInputStream(
                new ZstdInputStreamNoFinalizer(Files.newInputStream(bundlePath)))) {
            while (in.available() > 0) {
                var entry = in.readEntry();
                var len = entry.fileLen();
                var dstPath = destination.resolve(Path.of(entry.name()));
                if (Files.notExists(dstPath.getParent())) {
                    Files.createDirectories(dstPath.getParent());
                }
                try (var out = Files.newOutputStream(dstPath,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    var read = 0;
                    var toRead = len;
                    while (toRead > 0) {
                        read = in.read(buffer, 0, (int) Math.min(buffer.length, toRead));
                        toRead -= read;
                        out.write(buffer, 0, read);
                    }
                }

            }
        }
    }
}
