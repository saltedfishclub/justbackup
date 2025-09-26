package io.ib67.sfcraft.bundler;

public record BundleEntry(
        short attribute,
        long fileLen,
        String name
) {
    public static short ATTR_GUNZIP = 1;
    public static short MAGIC = (short) 0xA2E3;
}
