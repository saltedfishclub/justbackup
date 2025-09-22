package io.ib67.sfcraft;

public record Backup(
        String name,
        String backupKey,
        String type,
        String from,
        boolean incremental,
        long sizeTotal
) {
}
