package io.ib67.sfcraft.strategy;

import io.ib67.sfcraft.Backup;
import io.ib67.sfcraft.WorldDir;

import java.nio.file.Path;

public interface BackupStrategy {
    /**
     * create a backup
     * @param dir world to be bundled
     * @return a key to this backup
     */
    Backup createBackup(WorldDir dir, boolean compress);

    void recoverBackup(Backup backup, Path restorePath);
}
