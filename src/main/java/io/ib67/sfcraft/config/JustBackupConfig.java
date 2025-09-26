package io.ib67.sfcraft.config;

import org.apache.commons.lang3.Validate;

import java.util.List;

public record JustBackupConfig(
        long backupIntervalMinutes,
        int keepBackups,
        int compressionLevel,
        boolean allowGunzip,
        String temporaryBackupDir,
        StorageOption option
) {
    public JustBackupConfig {
        Validate.isTrue(backupIntervalMinutes > 0, "backup interval must be positive");
        Validate.isTrue(keepBackups > 0, "keep backups must be positive");
        Validate.isTrue(compressionLevel > 0, "compression level must be positive");
        Validate.notNull(option, "option cannot be null");
    }
}
