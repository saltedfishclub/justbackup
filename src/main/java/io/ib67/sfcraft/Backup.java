package io.ib67.sfcraft;

public record Backup(
        String name,
        String backupKey,
        boolean incremental,
        long sizeTotal
) {
}
