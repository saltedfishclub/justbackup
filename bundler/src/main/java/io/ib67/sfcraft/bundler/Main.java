package io.ib67.sfcraft.bundler;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import io.ib67.kiwi.ArgOpts;
import lombok.SneakyThrows;

import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.function.UnaryOperator;

public class Main {
    @SneakyThrows
    public static void main(String[] args) {
        var opts = ArgOpts.builder()
                .args(args).description("Tools for .jbp.zst format bundle").programName("jpack").build();
        var in = opts.string("in", "Input. Can be directory or jpack bundle", null);
        var out = opts.string("out", "Output. Can be jpack bundle or directory", null);
        var help = opts.bool("help", false);
        var allowGunzip = opts.bool("reasm",
                "Should we reassemble NBT files for maximum compression rate", true);
        var parallel = opts.bool("parallel", "Use parallelized bundler. This conflicts with compress options", false);
        var parallelThreshold = opts.integer("threshold", "The threshold of parallel bundling", 512);
        var parallelCompression = opts.integer("parallel-compression", "The worker threads used for parallel compression. Do not confuse this with --parallel, which focus on bundling.\n" +
                "This option will be ignored when --parallel is present.", 1);
        var compressionLevel = opts.integer("level", "Zstd compression level", Zstd.defaultCompressionLevel());
        var dictionaryPath = opts.string("dict", "Zstd dictionary file", null);
        if (help) {
            opts.printHelp(System.out);
            return;
        }
        var dict = (dictionaryPath != null) ? Files.readAllBytes(Path.of(dictionaryPath)) : null;
        if (in == null || out == null) {
            System.out.println("Both in and out should be provided");
            return;
        }
        var inPath = Path.of(in);
        var outPath = Path.of(out);
        if (!Files.exists(inPath)) {
            System.out.println("The input isn't exists.");
            return;
        }
        if (Files.exists(outPath)) {
            System.out.println("The output is already exists. Please move it to other places");
            return;
        }
        if (in.endsWith(".jbp.zst")) {
            BundleReader.builder().build().extract(inPath, outPath);
        } else if (out.endsWith(".jbp.zst")) {
            UnaryOperator<BundleWriter.BundleWriterBuilder> cfg = it -> it
                    .allowGunzip(allowGunzip)
                    .relativeRoot(inPath)
                    .dictionary(dict)
                    .compressionLevel(compressionLevel);
            if (!Files.isDirectory(inPath))
                throw new IllegalArgumentException("Your input should be a world directory.");
            List<Path> paths;
            try (var _paths = Files.walk(inPath)) {
                paths = _paths.toList();
            }
            if (parallel) {
                try (var fj = ForkJoinPool.commonPool()) {
                    BundleWriter.createBundleParallelized(
                            outPath.getParent(),
                            paths,
                            parallelThreshold, fj,
                            b -> cfg.apply(b).maxWorkers(0)
                    ).join();
                }
            } else {
                BundleWriter.createBundle(
                        outPath,
                        paths,
                        c -> cfg.apply(c).maxWorkers(parallelCompression)
                );
            }
        } else {
            System.out.println("Invalid arguments. You should provide a pair of jpack and directory to act as in/output.");
        }
    }
}
