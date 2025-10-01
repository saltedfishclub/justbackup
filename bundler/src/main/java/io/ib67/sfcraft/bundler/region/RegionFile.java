package io.ib67.sfcraft.bundler.region;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import it.unimi.dsi.fastutil.shorts.Short2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectSortedMap;
import lombok.RequiredArgsConstructor;
import net.jpountz.lz4.LZ4BlockOutputStream;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

public class RegionFile implements Closeable {

    @RequiredArgsConstructor
    public enum CompressType {
        GZIP(1), ZLIB(2), NONE(3), LZ4(4);
        public final int index;

        public static CompressType findByIndex(int i) {
            return switch (i) {
                case 1 -> GZIP;
                case 2 -> ZLIB;
                case 3 -> NONE;
                case 4 -> LZ4;
                default -> throw new IllegalArgumentException("Unknown compression type: " + i);
            };
        }
    }

    protected final Short2ObjectSortedMap<ByteBuf> regionData = new Short2ObjectAVLTreeMap<>();
    protected final ByteBufAllocator allocator;
    protected final ByteBuf timestamp;

    public RegionFile(ByteBufAllocator allocator) {
        this.allocator = allocator;
        timestamp = allocator.buffer(4096);
    }

    private short getChunkKey(int x, int z) {
        return (short) ((z & 0b111111) << 6 | (x & 0b111111));
    }

    // this retains the input bytebuf.
    public void writeChunk(int chunkX, int chunkZ, CompressType type, ByteBuf data) throws IOException {
        if (chunkX > 32 || chunkZ > 32 || chunkX < 0 || chunkZ < 0)
            throw new IllegalArgumentException("Invalid chunkX+chunkZ: " + chunkX + " " + chunkZ);
        var key = getChunkKey(chunkX, chunkZ);
        var buf = allocator.buffer(5 + data.readableBytes());
        if (type == CompressType.NONE) {
            buf.writeInt(data.readableBytes());
            buf.writeByte(type.index);
            buf.writeBytes(data);
        } else {
            try (var out = findCompressor(type, new ByteBufOutputStream(buf))) {
                while (data.readableBytes() > 0) data.readBytes(out, data.readableBytes());
                var head = allocator.buffer(5);
                head.writeInt(buf.readableBytes());
                head.writeByte(type.index);
                var composite =  allocator.compositeBuffer();
                composite.addComponent(true, head);
                composite.addComponent(true, buf);
            } catch (IOException e) {
                buf.release();
                throw e;
            }
        }
        regionData.put(key, buf);
    }

    private OutputStream findCompressor(CompressType type, OutputStream out) throws IOException {
        return switch (type) {
            case LZ4 -> new LZ4BlockOutputStream(out);
            case GZIP -> new GZIPOutputStream(out);
            case ZLIB -> new DeflaterOutputStream(out);
            default -> throw new IllegalArgumentException("Unknown compression type: " + type);
        };
    }

    public void updateChunkTime(int chunkX, int chunkZ, int timestamp) {
        if (chunkX > 32 || chunkZ > 32 || chunkX < 0 || chunkZ < 0)
            throw new IllegalArgumentException("Invalid chunkX+chunkZ: " + chunkX + " " + chunkZ);
        var index = (chunkX + 32 * chunkZ) * 4;
        this.timestamp.setInt(index, timestamp);
    }

    public ByteBuf writeOutput() {
        var sect = allocator.buffer(4096);
        sect.setZero(0, 4096);
        sect.writerIndex(4096);
        var compositeByteBuf = allocator.compositeBuffer(2 + regionData.size());
        var beginSector = 2;
        compositeByteBuf.addComponent(true, sect);
        timestamp.retain();
        timestamp.readerIndex(0);
        compositeByteBuf.addComponent(true, timestamp);
        for (var entry : regionData.short2ObjectEntrySet()) {
            var chunkKey = entry.getShortKey();
            var x = chunkKey & 0b111111;
            var z = (chunkKey >>> 6) & 0b111111;
            var chunk = entry.getValue();
            var size = chunk.readableBytes();
            var sizeSectors = Math.ceilDiv(size, 4096);
            var zeros = 4096 - (size % 4096);
            for (int i = 0; i < zeros; i++) {
                chunk.writeByte(0);
            }
            if (sizeSectors > 255) {
                throw new IllegalArgumentException("MCC not supported.");
            }
            if(sizeSectors < 0) {
                throw new IllegalArgumentException("Invalid sizeSectors: "+sizeSectors);
            }
            var begin = (x + 32 * z) * 4;
            sect.setByte(begin, (beginSector >>> 16) & 0xFF);
            sect.setByte(begin+1, (beginSector >>> 8) & 0xFF);
            sect.setByte(begin+2, beginSector & 0xFF);
            sect.setByte(begin+3, sizeSectors & 0xFF);
            chunk.retain(); // retain a local reference
            compositeByteBuf.addComponent(true, chunk);
            beginSector += sizeSectors;
        }
        return compositeByteBuf;
    }

    @Override
    public void close() throws IOException {
        timestamp.release();
        regionData.values().forEach(ByteBuf::release);
    }
}
