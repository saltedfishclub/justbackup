package io.ib67.sfcraft.bundler;

/**
 * Header Format:
 * u16 MAGIC u64 time varint attribute varlong length varint length of name
 * @param attribute
 * @param time
 * @param length
 * @param name
 */
public record BundleEntry(
        short attribute,
        long time,
        long length,
        String name
) {
    public static short ATTR_REASSEMBLE = 1;
    public static short MAGIC = (short) 0xA2E3;
}
