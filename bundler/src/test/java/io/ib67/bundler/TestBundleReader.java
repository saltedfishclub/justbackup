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
    public void testTombstoneRemovesFileOnRestore(@TempDir Path tmp) throws IOException {
        var src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("keep.txt"), "keep");
        Files.writeString(src.resolve("gone.txt"), "gone");
        var full = tmp.resolve("full.swb.zst");
        BundleWriter.createBundle(full, List.of(src.resolve("keep.txt"), src.resolve("gone.txt")),
                c -> c.relativeRoot(src));

        // gone.txt was deleted; the incremental carries only a tombstone for it
        var incr = tmp.resolve("incr.swb.zst");
        BundleWriter.createBundle(incr, List.of(), List.of("gone.txt"), c -> c.relativeRoot(src));

        var dest = tmp.resolve("dest");
        var reader = BundleReader.builder().build();
        reader.extract(full, dest);
        assertTrue(Files.exists(dest.resolve("gone.txt")), "base restore should lay down the file first");

        reader.extract(incr, dest);
        assertTrue(Files.exists(dest.resolve("keep.txt")), "unrelated files must survive the tombstone");
        assertFalse(Files.exists(dest.resolve("gone.txt")),
                "a tombstoned file must be removed on chained restore, not resurrected");
    }

    @Test
    public void testIgnoreDeletionsKeepsTombstonedFile(@TempDir Path tmp) throws IOException {
        var src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("keep.txt"), "keep");
        Files.writeString(src.resolve("gone.txt"), "gone");
        var full = tmp.resolve("full.swb.zst");
        BundleWriter.createBundle(full, List.of(src.resolve("keep.txt"), src.resolve("gone.txt")),
                c -> c.relativeRoot(src));
        var incr = tmp.resolve("incr.swb.zst");
        BundleWriter.createBundle(incr, List.of(), List.of("gone.txt"), c -> c.relativeRoot(src));

        var dest = tmp.resolve("dest");
        var reader = BundleReader.builder().ignoreDeletions(true).build();
        reader.extract(full, dest);
        reader.extract(incr, dest);

        assertTrue(Files.exists(dest.resolve("gone.txt")),
                "with ignoreDeletions the tombstone is skipped and the file is kept");
        assertEquals("gone", Files.readString(dest.resolve("gone.txt")));
        assertEquals("keep", Files.readString(dest.resolve("keep.txt")));
    }

    @Test
    public void testTombstoneForUnknownPathIsHarmless(@TempDir Path tmp) throws IOException {
        var src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("a.txt"), "a");
        var bundle = tmp.resolve("b.swb.zst");
        // tombstone a nested path that was never in the base backup
        BundleWriter.createBundle(bundle, List.of(src.resolve("a.txt")),
                List.of("region/r.0.0.mca"), c -> c.relativeRoot(src));

        var dest = tmp.resolve("dest");
        BundleReader.builder().build().extract(bundle, dest);
        assertEquals("a", Files.readString(dest.resolve("a.txt")));
    }

    @Test
    public void testTombstoneCannotEscapeDestination(@TempDir Path tmp) throws IOException {
        var outside = Files.writeString(tmp.resolve("outside.txt"), "precious");
        var bundle = tmp.resolve("evil.swb.zst");
        try (var zstd = new ZstdOutputStreamNoFinalizer(Files.newOutputStream(bundle), 3);
             var out = new EntryOutputStream(zstd)) {
            out.writeEntryHeader("../outside.txt", io.ib67.sfcraft.bundler.BundleEntry.ATTR_DELETE, 0,
                    System.currentTimeMillis());
        }
        var dest = tmp.resolve("dest");
        assertThrows(IOException.class, () -> BundleReader.builder().build().extract(bundle, dest));
        assertTrue(Files.exists(outside), "a tombstone must not delete files outside the destination");
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
    public void testTimestampWithHighBitSurvivesRoundTrip(@TempDir Path tmp) throws IOException {
        // 1798761600000 has 0xCE at bits 24-31; an int shift on that byte sign-extends and
        // makes the decoded time negative, which used to make extract() skip the entry as
        // "too old". ~half of all real-world dates land in such a band.
        var time = 1798761600000L;
        assertTrue(((time >> 24) & 0xFF) >= 0x80, "test vector must exercise the sign-extension path");
        var bundle = tmp.resolve("hibit.swb.zst");
        var payload = "kept".getBytes();
        try (var zstd = new ZstdOutputStreamNoFinalizer(Files.newOutputStream(bundle), 3);
             var out = new EntryOutputStream(zstd)) {
            out.writeEntryHeader("world.dat", (short) 0, payload.length, time);
            out.write(payload);
        }
        var dest = tmp.resolve("dest");
        BundleReader.builder().build().extract(bundle, dest);
        assertEquals("kept", Files.readString(dest.resolve("world.dat")),
                "entry must not be skipped just because its timestamp byte has the high bit set");
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
