package io.ib67.sfcraft.bundler;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictTrainer;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import io.ib67.kiwi.ArgOpts;
import io.ib67.sfcraft.bundler.region.RegionParser;
import io.ib67.sfcraft.bundler.trainer.ZstdRegionTrainer;
import io.netty.buffer.ByteBufAllocator;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import lombok.SneakyThrows;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.function.UnaryOperator;

public class Main {
    @SneakyThrows
    public static void main(String[] args) {
        var opts = ArgOpts.builder()
                .args(args).description("Tools for .swb.zst format bundle").programName("jpack").build();
        var in = opts.string("in", "Input. Can be directory or jpack bundle", null);
        var out = opts.string("out", "Output. Can be jpack bundle or directory", null);
        var help = opts.bool("help", false);
        var allowGunzip = opts.bool("reasm", "Should we reassemble NBT files for maximum compression rate", true);

        // useless parallel
        var parallel = opts.bool("parallel", "Use parallelized bundler. This conflicts with compress options", false);
        var parallelThreshold = opts.integer("threshold", "The threshold of parallel bundling", 512);

        // zstd options
        var parallelCompression = opts.integer("parallel-compression", "The worker threads used for parallel compression. Do not confuse this with --parallel, which focus on bundling.\n" +
                "This option will be ignored when --parallel is present.", 1);
        var compressionLevel = opts.integer("level", "Zstd compression level", Zstd.defaultCompressionLevel());

        // dictionary
        var dictionaryPath = opts.string("dict", "Zstd dictionary file", null);
        var train = opts.string("train", "Train zstd dictionary based on your map files. The value should be a directory which contains a lot of .mca files", null);
        var trainSampleSize = opts.integer("train-sample-size", "Train sample size (in total)", -1);
        var trainDictSize = opts.integer("train-dict-size", "Train dict size", -1);
        var stat = opts.bool("stat", "Count region and chunk sizes. You should set --train for this option to find region files", false);
        if (help) {
            opts.printHelp(System.out);
            return;
        }
        if (stat) {
            Objects.requireNonNull(train, "You should also set --train for this to work.");
            calculateStat(Path.of(train));
            return;
        }
        if (train != null) {
            if (trainDictSize < 0 || trainSampleSize < 0) {
                System.err.println("You should also set train-sample-size and train-dict-size.");
                System.err.println("TIP: use --stat to gather some useful information.");
                return;
            }
            train(train, trainSampleSize, trainDictSize);
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

        if (in.endsWith(".swb.zst")) {
            BundleReader.builder().build().extract(inPath, outPath);
        } else if (out.endsWith(".swb.zst")) {
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

    private static void calculateStat(Path inPath) throws IOException {
        try (var stream = Files.walk(inPath)) {
            var regions = stream
                    .filter(Files::isRegularFile)
                    .filter(it -> it.toString().endsWith("mca"))
                    .toList();
            var intFreqMap = new Int2IntOpenHashMap();
            for (Path region : regions) {
                try (var parser = new RegionParser(region, ByteBufAllocator.DEFAULT)) {
                    for (long packedEntry : parser.readAllValidChunks()) {
                        var entry = RegionParser.readEntry(packedEntry);
                        var occupiedSectors = entry & 0xFF;
                        intFreqMap.merge(occupiedSectors, 1, Integer::sum);
                    }
                } catch (IOException e) {
                    System.err.println("Skipping truncated region " + region + ": " + e.getMessage());
                }
            }
            var entrySet = intFreqMap.int2IntEntrySet().stream()
                    .sorted(Comparator.comparingInt(Int2IntMap.Entry::getIntValue).reversed())
                    .toList();
            var overallAppears = entrySet
                    .stream().mapToInt(Int2IntMap.Entry::getIntValue)
                    .sum();
            var all = entrySet.stream().mapToLong(it -> (long) it.getIntKey() * 4096 * it.getIntValue()).sum();
            System.out.println("All samples size in total: " + all+"B");
            System.out.println("Region chunk size stat (first 32):");
            for (int i = 0; i < Math.min(32, entrySet.size()); i++) {
                var entry = entrySet.get(i);
                var freq = String.format("%.2f", ((double) entry.getIntValue() / (double) overallAppears));
                System.out.println(" " + i + ". Sectors: " + entry.getIntKey() + "(" + entry.getIntKey() * 4096 + "KiB)" +
                        ", frequency: " + freq + ", count: " + entry.getIntValue());
            }
        }
    }

    private static void train(String train, int sampleSize, int dictSize) throws IOException {
        var path = Path.of(train);
        try (var stream = Files.walk(path);
             var trainer = new ZstdRegionTrainer(
                     new ZstdDictTrainer(sampleSize, dictSize),
                     ByteBufAllocator.DEFAULT)
        ) {
            var regions = stream
                    .filter(Files::isRegularFile)
                    .filter(it -> it.toString().endsWith("mca"))
                    .toList();

            for (var region : regions) {
                try {
                    trainer.feed(region);
                } catch (IOException e) {
                    System.err.println("Failed to train on " + region + ": " + e.getMessage() + ". Maybe sample is full..");
                    break;
                }
            }
            Files.write(Path.of("train_"+System.currentTimeMillis()+".train"), trainer.train());
        }
    }
}
