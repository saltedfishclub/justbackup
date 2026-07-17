package io.ib67.sfcraft.strategy;

import io.ib67.sfcraft.Backup;

import java.nio.file.Path;

public interface BackupStrategy {
    /**
     * Stores the finished bundle.
     *
     * @param subject     name of the backup subject
     * @param source      directory the bundle was created from
     * @param bundle      the bundle file to store; the caller deletes it afterwards
     * @param incremental whether the bundle only contains changed files
     * @param createdAt   epoch millis the backup was taken at
     * @return metadata describing the stored backup
     */
    Backup createBackup(String subject, Path source, Path bundle, boolean incremental, long createdAt);

    void recoverBackup(Backup backup, Path restorePath);

    void deleteBackup(Backup backup);

    boolean isAvailable();

    void close();
}
