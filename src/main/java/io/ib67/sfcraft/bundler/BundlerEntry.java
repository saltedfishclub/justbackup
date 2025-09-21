package io.ib67.sfcraft.bundler;

public record BundlerEntry(
        String fileName,
        boolean unGzipped,
        long fileLength,
        long offset
) {
    public static final int MAGIC = 0xBACEBACE;
}
