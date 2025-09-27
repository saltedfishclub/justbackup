package io.ib67.bundler;

import io.ib67.sfcraft.bundler.region.RegionFile;
import io.ib67.sfcraft.bundler.region.RegionFileReassembler;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class TestRegionReassembler {
    @Test
    public void testReassembler() throws IOException {
        byte[] bytes;
        try(var in = TestRegionReassembler.class.getClassLoader().getResourceAsStream("region/r.9.-5.mca")){
            bytes = in.readAllBytes();
        }
        try(var reasm = new RegionFileReassembler(Unpooled.wrappedBuffer(bytes))) {
            var result = reasm.writeReassembled(ByteBufAllocator.DEFAULT, RegionFile.CompressType.NONE);
        }
    }
}
