package io.ib67.sfcraft.strategy;

import io.ib67.sfcraft.Backup;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class BackupTracker implements BackupStrategy {
    protected final BackupStrategy underlying;
    @Getter
    protected final Map<String, Backup> trackedBackups;

    public BackupTracker(BackupStrategy underlying, Map<String, Backup> trackedBackups) {
        this.underlying = Objects.requireNonNull(underlying);
        this.trackedBackups = trackedBackups == null ? new HashMap<>() : trackedBackups;
    }

    @Override
    public Backup createBackup(Path from, Path pathToBundle) {
        var result = underlying.createBackup(from, pathToBundle);
        trackedBackups.put(result.backupKey(), result);
        return result;
    }

    @Override
    public void recoverBackup(Backup backup, Path restorePath) {
        underlying.recoverBackup(backup, restorePath);
    }

    @Override
    public void deleteBackup(Backup backup) {
        underlying.deleteBackup(backup);
        trackedBackups.remove(backup.backupKey());
    }

    @Override
    public boolean isAvailable() {
        return underlying.isAvailable();
    }

    @Override
    public void close() {
        underlying.close();
    }
}
