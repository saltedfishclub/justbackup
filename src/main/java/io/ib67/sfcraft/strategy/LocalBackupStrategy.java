package io.ib67.sfcraft.strategy;

import io.ib67.sfcraft.Backup;
import io.ib67.sfcraft.bundler.BundleReader;
import io.ib67.sfcraft.config.StorageOption;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.Validate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Log4j2
public class LocalBackupStrategy implements BackupStrategy {
    protected final StorageOption.Local option;
    protected final Path backupParentRoot;

    @SneakyThrows
    public LocalBackupStrategy(StorageOption.Local option) {
        this.option = option;
        backupParentRoot = Path.of(option.saveDir());
        Files.createDirectories(backupParentRoot);
    }

    @Override
    @SneakyThrows
    public Backup createBackup(String subject, Path source, Path bundle, boolean incremental, long createdAt) {
        var parent = backupParentRoot.resolve(subject);
        var fileName = bundle.getFileName().toString();
        var result = new Backup(
                fileName,
                Path.of(subject).resolve(fileName).normalize().toString(),
                "local", source.toString(), subject, incremental, createdAt, Files.size(bundle));
        if (Files.notExists(parent)) {
            Files.createDirectories(parent);
        }
        Files.move(bundle, parent.resolve(fileName));
        return result;
    }

    @Override
    @SneakyThrows
    public void recoverBackup(Backup backup, Path restorePath, boolean ignoreDeletions) {
        var backupFile = backupParentRoot.resolve(backup.backupKey());
        if (Files.notExists(backupFile)) throw new IllegalArgumentException("Backup " + backupFile + " does not exist");
        BundleReader.builder().ignoreDeletions(ignoreDeletions).build().extract(backupFile, restorePath);
    }

    @SneakyThrows
    @Override
    public void deleteBackup(Backup backup) {
        Validate.isTrue("local".equals(backup.type()), "Not a local backup: %s", backup.backupKey());
        var bundlePath = backupParentRoot.resolve(backup.backupKey());
        Files.deleteIfExists(bundlePath);
    }

    @Override
    @SneakyThrows
    public boolean isAvailable() {
        if (option.diskSizeQuotaBytes() == 0) return true;
        try (var f = Files.walk(backupParentRoot)) {
            if (f.filter(Files::isRegularFile).mapToLong(it -> {
                try {
                    return Files.size(it);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).sum() >= option.diskSizeQuotaBytes()) {
                log.warn("Backup disk quota ({} bytes) exceeded, refusing to create new backups!", option.diskSizeQuotaBytes());
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {

    }
}
