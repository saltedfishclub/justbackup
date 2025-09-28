package io.ib67.sfcraft.config;

import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.Validate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Log4j2
public record JustBackupConfig(
        boolean bundleFullAtStartup,
        boolean incremental,
        long backupIntervalMinutes,
        int keepBackups,
        int compressionLevel,
        int compressionWorkerThreads,
        boolean allowGunzip,
        String temporaryBackupDir,
        String zstdDictPath,
        Map<String, String> backupSubjects,
        StorageOption option
) {
    @SneakyThrows
    public JustBackupConfig {
        Validate.isTrue(backupIntervalMinutes > 0, "backup interval must be positive");
        Validate.isTrue(keepBackups > 0, "keep backups must be positive");
        Validate.isTrue(compressionWorkerThreads >= 0, "compression worker threads must be positive");
        Validate.isTrue(compressionLevel > 0, "compression level must be positive");
        if (backupSubjects == null) backupSubjects = new HashMap<>();
        if (backupSubjects.isEmpty()) {
            log.warn("Backup subjects are not configured. The mod will not work!");
        }
        Validate.notNull(option, "option cannot be null");
    }
}
