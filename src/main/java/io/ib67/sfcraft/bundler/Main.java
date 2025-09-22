package io.ib67.sfcraft.bundler;

import io.ib67.kiwi.ArgOpts;
import io.ib67.sfcraft.WorldDir;

import java.nio.file.Files;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        var opts = ArgOpts.builder()
                .args(args).description("Tools for .jpack format bundle").programName("jpack").build();
        var in = opts.string("in", "Input. Can be directory or jpack bundle", null);
        var out = opts.string("out", "Output. Can be jpack bundle or directory", null);
        var help = opts.bool("help", false);
        var allowGunzip = opts.bool("gunzip",
                "Should we recompress NBT files for maximum compression rate (depends on compress)", true);
        var compress = opts.bool("compress", "Should we compress?", true);
        var parallel = opts.bool("parallel", "Use parallelized bundler. This conflicts with compress options", false);
        if (help) {
            opts.printHelp(System.out);
            return;
        }
        if (allowGunzip && !compress) {
            System.out.println("allowGunzip depends on compress feature.");
            return;
        }
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
        if (in.endsWith(".jpack")) {
            Bundle.unbundleFile(inPath, outPath);
        } else if (out.endsWith(".jpack")) {
            if (!Files.isDirectory(inPath))
                throw new IllegalArgumentException("Your input should be a world directory.");
            new Bundle(new WorldDir(inPath))
                    .allowGunzip(allowGunzip)
                    .compress(compress)
                    .allowParallel(parallel)
                    .buildBundle(outPath);
        } else {
            System.out.println("Invalid arguments. You should provide a pair of jpack and directory to act as in/output.");
        }
    }
}
