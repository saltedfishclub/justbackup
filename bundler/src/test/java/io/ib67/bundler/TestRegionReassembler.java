package io.ib67.bundler;

import io.ib67.sfcraft.bundler.region.RegionFile;
import io.ib67.sfcraft.bundler.region.RegionParser;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class TestRegionReassembler {
    @Test
    public void testReassembler() throws IOException {
        testMethod("region/r.9.-5.mca");
        testMethod("region/r.-1.-1.mca");
    }

    private static void testMethod(String key) throws IOException {
        byte[] bytes;
        try(var in = TestRegionReassembler.class.getClassLoader().getResourceAsStream(key)){
            bytes = in.readAllBytes();
        }
        try(var reasm = new RegionParser(Unpooled.wrappedBuffer(bytes), ByteBufAllocator.DEFAULT)) {
            var result = reasm.writeReassembled(RegionFile.CompressType.NONE);
            try(var foc = FileChannel.open(Path.of("./test.mca"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                result.readBytes(foc, 0, result.readableBytes());
            }
            result.readerIndex(0);
            try(var reader = new RegionFile(ByteBufAllocator.DEFAULT)) {
                result = reasm.writeReassembled(RegionFile.CompressType.ZLIB);
            }
        }
    }
}
