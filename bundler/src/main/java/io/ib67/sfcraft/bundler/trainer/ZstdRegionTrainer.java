package io.ib67.sfcraft.bundler.trainer;

import com.github.luben.zstd.ZstdDictTrainer;
import io.ib67.sfcraft.bundler.region.RegionParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import lombok.SneakyThrows;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class ZstdRegionTrainer implements Closeable {
    protected final ZstdDictTrainer trainer;
    protected final ByteBufAllocator allocator;
    protected final ByteBuf decompressBuffer;

    public ZstdRegionTrainer(ZstdDictTrainer trainer, ByteBufAllocator allocator) {
        this.trainer = Objects.requireNonNull(trainer);
        this.allocator = allocator;
        this.decompressBuffer = Unpooled.buffer();
    }

    public void feed(Path pathRegion) throws IOException {
        if(Files.size(pathRegion) < 8192) return;;
        try (var regionParser = new RegionParser(pathRegion, allocator)) {
            var validChunks = regionParser.readAllValidChunks();
            var buffer = decompressBuffer;
            for (var idx = 0; idx < validChunks.size(); idx++) {
                regionParser.readChunkDecompress(RegionParser.readEntry(validChunks.getLong(idx)), buffer);
                if(!trainer.addSample(buffer.array())){
                    throw new IOException("Cannot add sample idx "+idx+" from "+pathRegion+", size: "+buffer.readableBytes());
                }
                buffer.clear();
            }
        }
    }

    public byte[] train() {
        return trainer.trainSamples();
    }

    @Override
    public void close() throws IOException {
        decompressBuffer.release();
    }
}
