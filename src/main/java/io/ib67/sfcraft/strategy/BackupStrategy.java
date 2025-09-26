package io.ib67.sfcraft.strategy;

import io.ib67.sfcraft.Backup;

import java.nio.file.Path;

public interface BackupStrategy {
    /**
     * create a backup
     * @return a key to this backup
     */
    Backup createBackup(Path source, Path bundle);

    void recoverBackup(Backup backup, Path restorePath);

    void deleteBackup(Backup backup);

    boolean isAvailable();

    void close();
}
