package io.ib67.sfcraft.bundler;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import io.ib67.sfcraft.bundler.region.RegionFile;
import io.ib67.sfcraft.bundler.region.RegionParser;
import io.netty.buffer.ByteBufAllocator;
import lombok.Builder;

import java.io.IOException;
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

    public void extract(Path bundle, Path destination) throws IOException {
        var symbolsToLink = new HashMap<String, String>();
        var readMaps = new HashMap<String, Long>();
        try (var zstd = new ZstdInputStreamNoFinalizer(Files.newInputStream(bundle, StandardOpenOption.READ));
             var in = new EntryInputStream(zstd)) {
            if (dict != null) zstd.setDict(dict);
            while (in.available() > 0) {
                var entry = in.readEntry();
                var time = entry.time();
                if (time < minTime || time > maxTime) {
                    System.err.println("Skipping entry " + entry.name() + " (too old or too new)");
                    continue;
                }
                var lastSeen = readMaps.get(entry.name());
                if (lastSeen != null && skipOldEntry && entry.time() < lastSeen) {
                    System.err.println("Skipping entry " + entry.name() + " (older than )");
                    continue;
                }
                readMaps.put(entry.name(), time);

                var path = destination.resolve(entry.name()); // todo prevent path underflow
                var parentDir = path.getParent();
                if (Files.notExists(parentDir)) Files.createDirectories(parentDir);
                if ((entry.attribute() & BundleEntry.ATTR_SYMLINK) != 0) {
                    var toRead = (int) entry.length();
                    var strArr = new byte[toRead];
                    while ((toRead -= in.read(strArr, (int) (entry.length() - toRead), toRead)) > 0) ;
                    var target = new String(strArr);
                    var targetPath = Path.of(target);
                    Files.createSymbolicLink(targetPath, path);
                } else if ((entry.attribute() & BundleEntry.ATTR_REASSEMBLE) != 0) {
                    var entryData = allocator.buffer();
                    var toRead = entry.length();
                    var buffer = this.buffer;
                    while (toRead > 0) {
                        var read = in.read(buffer, 0, (int) Math.min(buffer.length, toRead));
                        toRead -= read;
                        entryData.writeBytes(buffer, 0, read);
                    }
                    try (var reassembler = new RegionParser(entryData, allocator)) {
                        var result = reassembler.writeReassembled(RegionFile.CompressType.ZLIB);
                        try (var fc = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                            result.readBytes(fc, 0, result.readableBytes());
                        } finally {
                            result.release();
                        }
                    } catch (Exception e) {
                        try (var ch = FileChannel.open(Path.of("dump.mca"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                            entryData.readerIndex(0);
                            entryData.readBytes(ch, 0, entryData.readableBytes());
                        }
                        throw new IOException("Error while reassembling " + entry.name(), e);
                    }
                } else {
                    writePlain(path, entry, in);
                }
            }

        }
    }

    private void writePlain(Path path, BundleEntry entry, EntryInputStream in) throws IOException {
        var buffer = this.buffer;
        try (var fc = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var toRead = entry.length();
            while (toRead > 0) {
                var read = in.read(buffer, 0, (int) Math.min(buffer.length, toRead));
                toRead -= read;
                fc.write(buffer, 0, read);
            }
        }
    }
}
