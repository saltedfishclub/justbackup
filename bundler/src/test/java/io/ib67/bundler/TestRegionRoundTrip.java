package io.ib67.bundler;

import io.ib67.sfcraft.bundler.region.RegionFile;
import io.ib67.sfcraft.bundler.region.RegionParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulates the full backup->restore cycle for region files: vanilla mca -> reassembled
 * uncompressed (what goes into the bundle) -> reassembled ZLIB (what restore writes back),
 * then verifies every chunk's uncompressed NBT is byte-identical and lives in the same
 * header slot as in the original file.
 */
public class TestRegionRoundTrip {
    @Test
    public void roundTripPreservesEveryChunk() throws IOException {
        roundTrip("region/r.9.-5.mca");
        roundTrip("region/r.-1.-1.mca");
    }

    private static void roundTrip(String resource) throws IOException {
        byte[] originalBytes;
        try (var in = TestRegionRoundTrip.class.getClassLoader().getResourceAsStream(resource)) {
            originalBytes = in.readAllBytes();
        }
        var alloc = ByteBufAllocator.DEFAULT;

        // backup side: vanilla -> NONE bundle payload
        ByteBuf intermediate;
        try (var parser = new RegionParser(Unpooled.wrappedBuffer(originalBytes), alloc)) {
            intermediate = parser.writeReassembled(RegionFile.CompressType.NONE);
        }
        // restore side: bundle payload -> ZLIB region written to disk
        ByteBuf restored;
        try (var parser = new RegionParser(intermediate, alloc)) {
            restored = parser.writeReassembled(RegionFile.CompressType.ZLIB);
        } finally {
            intermediate.release();
        }

        try (var originalParser = new RegionParser(Unpooled.wrappedBuffer(originalBytes), alloc);
             var restoredParser = new RegionParser(restored.readerIndex(0).retain(), alloc)) {
            var originalChunks = collect(originalParser);
            var restoredChunks = collect(restoredParser);
            assertFalse(originalChunks.isEmpty(), resource + " should contain chunks");
            assertEquals(originalChunks.keySet(), restoredChunks.keySet(),
                    "restored file must populate exactly the same chunk slots (no transposition)");
            for (var entry : originalChunks.entrySet()) {
                var origNbt = decompress(originalParser, entry.getValue(), alloc);
                var restNbt = decompress(restoredParser, restoredChunks.get(entry.getKey()), alloc);
                try {
                    assertEquals(origNbt, restNbt, "chunk " + entry.getKey() + " must survive the round trip unchanged");
                } finally {
                    origNbt.release();
                    restNbt.release();
                }
            }
            // timestamp table is copied verbatim
            var origTimes = Unpooled.wrappedBuffer(originalBytes).slice(4096, 4096);
            var restTimes = restored.slice(4096, 4096);
            assertEquals(origTimes, restTimes, "chunk timestamps must survive the round trip");
        } finally {
            restored.release();
        }
    }

    /** chunkXZ -> header entry */
    private static Map<Short, Integer> collect(RegionParser parser) {
        var result = new HashMap<Short, Integer>();
        var chunks = parser.readAllValidChunks();
        for (int i = 0; i < chunks.size(); i++) {
            var key = chunks.getLong(i);
            result.put(RegionParser.readChunkXZ(key), RegionParser.readEntry(key));
        }
        return result;
    }

    private static ByteBuf decompress(RegionParser parser, int entry, ByteBufAllocator alloc) throws IOException {
        var buf = alloc.buffer();
        parser.readChunkDecompress(entry, buf);
        return buf;
    }
}
