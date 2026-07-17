package io.ib67.sfcraft.bundler;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import io.ib67.sfcraft.bundler.region.RegionFile;
import io.ib67.sfcraft.bundler.region.RegionParser;
import io.netty.buffer.ByteBufAllocator;
import lombok.Builder;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;

public class BundleReader {
    protected final long maxTime;
    protected final long minTime;
    protected final boolean linkSymbol;
    private final byte[] buffer = new byte[4096];
    protected final boolean skipOldEntry;
    protected final ByteBufAllocator allocator;
    protected final byte[] dict;

    @Builder
    public BundleReader(long maxTime, long minTime, boolean linkSymbol, boolean skipOldEntry, ByteBufAllocator allocator, byte[] dict) {
        this.maxTime = maxTime == 0 ? Long.MAX_VALUE : maxTime;
        this.minTime = minTime;
        this.linkSymbol = linkSymbol;
        this.skipOldEntry = skipOldEntry;
        this.allocator = allocator == null ? ByteBufAllocator.DEFAULT : allocator;
        this.dict = dict;
    }

    /**
     * Extracts a bundle into {@code destination}. Existing files are overwritten, which is
     * what makes chained restores work: apply the full base first, then each incremental in
     * order — later bundles win.
     */
    public void extract(Path bundle, Path destination) throws IOException {
        var readMaps = new HashMap<String, Long>();
        var destRoot = destination.toAbsolutePath().normalize();
        try (var zstd = new ZstdInputStreamNoFinalizer(Files.newInputStream(bundle, StandardOpenOption.READ));
             var in = new EntryInputStream(zstd)) {
            if (dict != null) zstd.setDict(dict);
            while (in.available() > 0) {
                var entry = in.readEntry();
                var time = entry.time();
                if (time < minTime || time > maxTime) {
                    System.err.println("Skipping entry " + entry.name() + " (too old or too new)");
                    skipExactly(in, entry.length());
                    continue;
                }
                var lastSeen = readMaps.get(entry.name());
                if (lastSeen != null && skipOldEntry && entry.time() < lastSeen) {
                    System.err.println("Skipping entry " + entry.name() + " (older than seen)");
                    skipExactly(in, entry.length());
                    continue;
                }
                readMaps.put(entry.name(), time);

                var path = resolveSafely(destRoot, entry.name());
                var parentDir = path.getParent();
                if (Files.notExists(parentDir)) Files.createDirectories(parentDir);
                if ((entry.attribute() & BundleEntry.ATTR_SYMLINK) != 0) {
                    var strArr = new byte[(int) entry.length()];
                    readExactly(in, strArr);
                    var targetPath = Path.of(new String(strArr));
                    Files.deleteIfExists(path);
                    Files.createSymbolicLink(path, targetPath);
                } else if ((entry.attribute() & BundleEntry.ATTR_REASSEMBLE) != 0) {
                    var entryData = allocator.buffer();
                    try {
                        var toRead = entry.length();
                        var buffer = this.buffer;
                        while (toRead > 0) {
                            var read = in.read(buffer, 0, (int) Math.min(buffer.length, toRead));
                            if (read < 0) throw new EOFException("Truncated bundle while reading " + entry.name());
                            toRead -= read;
                            entryData.writeBytes(buffer, 0, read);
                        }
                        try (var reassembler = new RegionParser(entryData, allocator)) {
                            var result = reassembler.writeReassembled(RegionFile.CompressType.ZLIB);
                            try (var fc = FileChannel.open(path, StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                                result.readBytes(fc, 0, result.readableBytes());
                            } finally {
                                result.release();
                            }
                        } catch (Exception e) {
                            var dump = Files.createTempFile("justbackup_dump_", ".mca");
                            try (var ch = FileChannel.open(dump, StandardOpenOption.WRITE)) {
                                entryData.readerIndex(0);
                                entryData.readBytes(ch, 0, entryData.readableBytes());
                            }
                            throw new IOException("Error while reassembling " + entry.name() + ", dumped to " + dump, e);
                        }
                    } finally {
                        entryData.release();
                    }
                } else {
                    writePlain(path, entry, in);
                }
            }
        }
    }

    /**
     * Resolves an entry name inside the destination, refusing names that escape it
     * (absolute paths or {@code ..} traversal).
     */
    private static Path resolveSafely(Path destRoot, String entryName) throws IOException {
        var resolved = destRoot.resolve(entryName).normalize();
        if (!resolved.startsWith(destRoot)) {
            throw new IOException("Entry " + entryName + " escapes the destination directory, refusing to extract");
        }
        return resolved;
    }

    private static void readExactly(InputStream in, byte[] target) throws IOException {
        int off = 0;
        while (off < target.length) {
            int read = in.read(target, off, target.length - off);
            if (read < 0) throw new EOFException("Truncated bundle");
            off += read;
        }
    }

    private void skipExactly(InputStream in, long length) throws IOException {
        var buffer = this.buffer;
        while (length > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, length));
            if (read < 0) throw new EOFException("Truncated bundle");
            length -= read;
        }
    }

    private void writePlain(Path path, BundleEntry entry, EntryInputStream in) throws IOException {
        var buffer = this.buffer;
        try (var fc = Files.newOutputStream(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            var toRead = entry.length();
            while (toRead > 0) {
                var read = in.read(buffer, 0, (int) Math.min(buffer.length, toRead));
                if (read < 0) throw new EOFException("Truncated bundle while reading " + entry.name());
                toRead -= read;
                fc.write(buffer, 0, read);
            }
        }
    }
}
