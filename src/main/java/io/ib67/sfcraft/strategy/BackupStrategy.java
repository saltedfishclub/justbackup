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

    /**
     * Extracts {@code backup} into {@code restorePath}.
     *
     * @param ignoreDeletions when true, tombstone entries are skipped, so files deleted since
     *                        the base backup are kept instead of removed on restore
     */
    void recoverBackup(Backup backup, Path restorePath, boolean ignoreDeletions);

    void deleteBackup(Backup backup);

    boolean isAvailable();

    void close();
}
