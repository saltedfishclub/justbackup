package io.ib67.bundler;

import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import io.ib67.sfcraft.bundler.BundleReader;
import io.ib67.sfcraft.bundler.BundleWriter;
import io.ib67.sfcraft.bundler.EntryOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestBundleReader {

    @Test
    public void testChainedOverlayRestore(@TempDir Path tmp) throws IOException {
        var src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("a.txt"), "version-1-that-is-long");
        Files.writeString(src.resolve("b.txt"), "untouched");
        var fullBundle = tmp.resolve("full.swb.zst");
        BundleWriter.createBundle(fullBundle, List.of(src.resolve("a.txt"), src.resolve("b.txt")),
                c -> c.relativeRoot(src));

        // shorter content verifies the overlay truncates instead of leaving a stale tail
        Files.writeString(src.resolve("a.txt"), "v2");
        var incrBundle = tmp.resolve("incr.swb.zst");
        BundleWriter.createBundle(incrBundle, List.of(src.resolve("a.txt")),
                c -> c.relativeRoot(src));

        var dest = tmp.resolve("dest");
        var reader = BundleReader.builder().build();
        reader.extract(fullBundle, dest);
        reader.extract(incrBundle, dest);

        assertEquals("v2", Files.readString(dest.resolve("a.txt")));
        assertEquals("untouched", Files.readString(dest.resolve("b.txt")));
    }

    @Test
    public void testZipSlipIsRejected(@TempDir Path tmp) throws IOException {
        var bundle = tmp.resolve("evil.swb.zst");
        var payload = "owned".getBytes();
        try (var zstd = new ZstdOutputStreamNoFinalizer(Files.newOutputStream(bundle), 3);
             var out = new EntryOutputStream(zstd)) {
            out.writeEntryHeader("../evil.txt", (short) 0, payload.length, System.currentTimeMillis());
            out.write(payload);
        }
        var dest = tmp.resolve("dest");
        var ex = assertThrows(IOException.class,
                () -> BundleReader.builder().build().extract(bundle, dest));
        assertTrue(ex.getMessage().contains("escapes"), ex.getMessage());
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
    }

    @Test
    public void testSymlinkIsCreatedAtLinkPath(@TempDir Path tmp) throws IOException {
        var src = Files.createDirectories(tmp.resolve("src"));
        var outside = Files.writeString(tmp.resolve("outside.txt"), "outside");
        var link = src.resolve("link");
        Files.createSymbolicLink(link, outside);

        var bundle = tmp.resolve("sym.swb.zst");
        BundleWriter.createBundle(bundle, List.of(link), c -> c.relativeRoot(src));

        var dest = tmp.resolve("dest");
        BundleReader.builder().build().extract(bundle, dest);
        assertTrue(Files.isSymbolicLink(dest.resolve("link")),
                "the symlink itself must be recreated at the link's path, not at its target");
    }

    @Test
    public void testTruncatedEntryThrowsInsteadOfLooping(@TempDir Path tmp) throws IOException {
        var bundle = tmp.resolve("truncated.swb.zst");
        try (var zstd = new ZstdOutputStreamNoFinalizer(Files.newOutputStream(bundle), 3);
             var out = new EntryOutputStream(zstd)) {
            out.writeEntryHeader("short.bin", (short) 0, 100, System.currentTimeMillis());
            out.write(new byte[10]); // 90 bytes missing
        }
        var dest = tmp.resolve("dest");
        assertThrows(EOFException.class,
                () -> BundleReader.builder().build().extract(bundle, dest));
    }
}
