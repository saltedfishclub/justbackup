package io.ib67.sfcraft;

import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * @param subject   name of the backup subject this bundle belongs to; null on entries created
 *                  by older versions (those are treated as standalone full backups)
 * @param createdAt epoch millis of creation; 0 on legacy entries
 */
public record Backup(
        String name,
        String backupKey,
        String type,
        String from,
        String subject,
        boolean incremental,
        long createdAt,
        long sizeTotal
) {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public boolean legacy() {
        return subject == null || createdAt <= 0;
    }

    public Component toText() {
        var size = Math.round(((sizeTotal / 1024.0) / 1024.0) * 100) * 0.01;
        var sb = new StringBuilder()
                .append(incremental ? "INCR" : "FULL")
                .append(", ").append(size).append("MiB")
                .append(", type: ").append(type);
        if (createdAt > 0) {
            sb.append(", at ").append(TIME_FORMAT.format(Instant.ofEpochMilli(createdAt)));
        }
        return Component.nullToEmpty(sb.toString());
    }
}
