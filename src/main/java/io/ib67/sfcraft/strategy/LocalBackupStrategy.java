package io.ib67.sfcraft.strategy;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import io.ib67.sfcraft.Backup;
import io.ib67.sfcraft.WorldDir;
import io.ib67.sfcraft.bundler.*;
import io.ib67.sfcraft.config.StorageOption;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.Validate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

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
    public Backup createBackup(Path from, Path pathToBundle) {
        var result = new Backup(
                pathToBundle.getFileName().toString(),
                pathToBundle.getFileName().toString(),
                "local", from.toString(), false, Files.size(pathToBundle));
        Files.move(pathToBundle, backupParentRoot.resolve(pathToBundle.getFileName()));
        return result;
    }

    @Override
    @SneakyThrows
    public void recoverBackup(Backup backup, Path restorePath) {
        // check if it is compressed
        var backupFile = backupParentRoot.resolve(backup.backupKey());
        if (Files.notExists(backupFile)) throw new IllegalArgumentException("Backup " + backupFile + " does not exist");
        Bundle.unbundleFile(backupFile, restorePath);
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
