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
    public Backup createBackup(String subject, Path from, Path pathToBundle) {
        var parent = backupParentRoot.resolve(subject);
        var result = new Backup(
                pathToBundle.getFileName().toString(),
                Path.of(subject).resolve(pathToBundle.getFileName()).normalize().toString(),
                "local", from.toString(), false, Files.size(pathToBundle));
        if(Files.notExists(parent)) {
            Files.createDirectories(parent);
        }
        //todo new name
        Files.move(pathToBundle, parent.resolve(pathToBundle.getFileName()));
        return result;
    }

    @Override
    @SneakyThrows
    public void recoverBackup(Backup backup, Path restorePath) {
        // check if it is compressed
        var backupFile = backupParentRoot.resolve(backup.backupKey());
        if (Files.notExists(backupFile)) throw new IllegalArgumentException("Backup " + backupFile + " does not exist");
        BundleReader.builder().build().extract(backupFile, restorePath);
    }

    @SneakyThrows
    @Override
    public void deleteBackup(Backup backup) {
        Validate.isTrue("local".equals(backup.backupKey()));
        var bundlePath = backupParentRoot.resolve(backup.backupKey());
        Files.deleteIfExists(bundlePath);
    }

    @Override
    @SneakyThrows
    public boolean isAvailable() {
        if (option.diskSizeQuotaBytes() == 0) return true;
        try (var f = Files.walk(backupParentRoot)) {
            if (f.mapToLong(it->{
                try {
                    return Files.size(it);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).sum() >= option.diskSizeQuotaBytes()) {
                log.warn("Have no space to set another backup! ");
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {

    }
}
