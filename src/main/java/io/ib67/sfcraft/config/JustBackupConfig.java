package io.ib67.sfcraft.config;

import org.apache.commons.lang3.Validate;

import java.util.List;

public record JustBackupConfig(
        long backupIntervalMinutes,
        int keepBackups,
        boolean allowParallel,
        boolean compress,
        StorageOption option
) {
    public JustBackupConfig {
        Validate.isTrue(backupIntervalMinutes > 0, "backup interval must be positive");
        Validate.isTrue(keepBackups > 0, "keep backups must be positive");
        Validate.notNull(option, "option cannot be null");
    }
}
