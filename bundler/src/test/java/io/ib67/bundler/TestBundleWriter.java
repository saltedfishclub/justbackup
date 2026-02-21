package io.ib67.bundler;

import io.ib67.sfcraft.bundler.BundleWriter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class TestBundleWriter {
    @Test
    public void testWriteBundle() throws IOException {
        var file = Path.of("/home/icybear/IdeaProjects/justbackup/bundler/src/test/resources/region/r.9.-5.mca");
        BundleWriter.createBundle(Path.of("/tmp/test.out"),
                List.of(file),
                c -> c.allowGunzip(true).relativeRoot(file.getParent()));
    }
}
