package io.ib67.sfcraft.strategy;

import io.ib67.sfcraft.Backup;
import io.ib67.sfcraft.Globals;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps a storage strategy with an in-memory index of known backups, persisted atomically
 * to {@code indexPath} after every mutation. The map is concurrent: the backup executor
 * writes while the main thread reads it for commands and tab completion.
 */
@Log4j2
public class BackupTracker implements BackupStrategy {
    protected final BackupStrategy underlying;
    @Getter
    protected final Map<String, Backup> trackedBackups = new ConcurrentHashMap<>();
    protected final Path indexPath;

    public BackupTracker(BackupStrategy underlying, Map<String, Backup> initial, Path indexPath) {
        this.underlying = Objects.requireNonNull(underlying);
        this.indexPath = Objects.requireNonNull(indexPath);
        if (initial != null) {
            initial.forEach((k, v) -> {
                if (k != null && v != null) trackedBackups.put(k, v);
            });
        }
    }

    @Override
    public Backup createBackup(String subject, Path source, Path bundle, boolean incremental, long createdAt) {
        var result = underlying.createBackup(subject, source, bundle, incremental, createdAt);
        trackedBackups.put(result.backupKey(), result);
        persist();
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
        persist();
    }

    /**
     * Writes the index to a temp file first and atomically moves it into place, so a crash
     * mid-write can never leave a corrupt index behind (which would disable the mod on the
     * next startup).
     */
    protected synchronized void persist() {
        try {
            var tmp = indexPath.resolveSibling(indexPath.getFileName() + ".tmp");
            Files.writeString(tmp, Globals.GSON.toJson(trackedBackups));
            try {
                Files.move(tmp, indexPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Cannot persist backup index to {}", indexPath, e);
        }
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
