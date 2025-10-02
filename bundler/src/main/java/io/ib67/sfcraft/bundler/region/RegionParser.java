package io.ib67.sfcraft.bundler.region;

import io.netty.buffer.*;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.jpountz.lz4.LZ4BlockInputStream;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.DeflaterInputStream;
import java.util.zip.GZIPInputStream;

public class RegionParser implements Closeable {
    protected final ByteBuf source;
    protected final byte[] sectorBuffer = new byte[4096];
    protected final ByteBufAllocator allocator;

    public RegionParser(Path file, ByteBufAllocator allocator) throws IOException {
        this.allocator = allocator;
        var size = (int) Files.size(file);
        if (size < 8192) throw new IOException("The region file " + file + " has been truncated");
        if (size > 8388608 * 2) throw new IOException("The region file " + file + " is too large"); //todo bug
        source = allocator.buffer(size);

        try (var fc = FileChannel.open(file, StandardOpenOption.READ)) {
            var toRead = size;
            while (toRead > 0) {
                toRead -= source.writeBytes(fc, 0L, (int) size);
            }
        }
    }

    public RegionParser(ByteBuf source, ByteBufAllocator allocator) {
        this.source = source;
        this.allocator = allocator;
        source.retain();
    }

    // the lower 32bit is the entry content
    // upper 32bit is the chunkX <<< 6 | chunkZ (12 bits in total)
    public LongList readAllValidChunks() {
        var result = new LongArrayList();
        source.readerIndex(0);
        for (int i = 0; i < 1024; i++) {
            var entry = source.readInt();
            if (entry == 0) continue;
            var x = i % 32;
            var z = Math.max(0, (i - x) / 32);
            result.add(((long) ((x << 6) | z) << 32) | entry);
        }
        return result;
    }

    /**
     * @return kind of compression.
     */
    public int readChunk(int entry, ByteBuf buf) {
        var offset = readOffset(entry);
        source.readerIndex(offset * 4096);
        // sector info
        var sectorLen = source.readInt();
        var compressType = source.readByte();
        if (compressType < 0) {
            // mcc todo
        }
        if (sectorLen == 0) return compressType;
        buf.writeBytes(source, sectorLen - 1);
        return compressType;
    }

    public void readChunkDecompress(int entry, ByteBuf buf) throws IOException {
        var compressed = allocator.buffer();
        var compressType = readChunk(entry, compressed);
        try (var decompressor = getDecompressorByType(compressType, compressed)) {
            while (buf.writeBytes(decompressor, 4096) != -1) ;
        } finally {
            compressed.release();
        }
    }

    public ByteBuf writeReassembled(RegionFile.CompressType newCompressType) throws IOException {
        var chunks = readAllValidChunks();
        var alloc = allocator;
        var compressed = alloc.buffer();
        var uncompressed = alloc.buffer();
        try (var regionFile = new RegionFile(alloc)) {
            // read times
            source.readerIndex(4096);
            source.readBytes(sectorBuffer);
            regionFile.timestamp.clear();
            regionFile.timestamp.writeBytes(sectorBuffer);
            for (int i = 0; i < chunks.size(); i++) {
                compressed.clear();
                uncompressed.clear();
                var key = chunks.getLong(i);
                var chunkXZ = readChunkXZ(key);
                var entry = readEntry(key);
                short x = (short) (chunkXZ & 0x3F);
                short z = (short) ((chunkXZ >>> 6) & 0x3F);
                var compressType = readChunk(entry, compressed);
                try (var decompressor = getDecompressorByType(compressType, compressed)) {
                    while (uncompressed.writeBytes(decompressor, 4096) != -1) ;
                    regionFile.writeChunk(x, z, newCompressType, uncompressed);
                }
            }
            return regionFile.writeOutput();
        } finally {
            uncompressed.release();
            compressed.release();
        }
    }

    private InputStream getDecompressorByType(int compressionType, ByteBuf in) throws IOException {
        return switch (compressionType) {
            case 1 -> new GZIPInputStream(new ByteBufInputStream(in));
            case 2 -> new DeflaterInputStream(new ByteBufInputStream(in));
            case 3 -> new ByteBufInputStream(in);
            case 4 -> new LZ4BlockInputStream(new ByteBufInputStream(in));
            // user defined compression algorithm, not going to support.
            // (or mcc)
            default -> throw new IOException("Unsupported compression type. " + compressionType);
        };
    }

    public static short readChunkXZ(long packedEntry) {
        packedEntry = packedEntry >> 32;
        return (short) (packedEntry & 0xFFF);
    }

    public static int readEntry(long packedEntry) {
        return (int) (packedEntry & 0xFFFFFFFFL);
    }

    public static int readOffset(int headerEntry) {
        return (headerEntry >> 8);
    }

    public static int readSize(int headerSize) {
        return headerSize & 0xFF;
    }

    @Override
    public void close() throws IOException {
        source.release();
    }
}
