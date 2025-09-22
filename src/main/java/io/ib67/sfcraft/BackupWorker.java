package io.ib67.sfcraft;

import io.ib67.sfcraft.bundler.Bundle;
import io.ib67.sfcraft.config.JustBackupConfig;
import io.ib67.sfcraft.mixin.LevelStorageAccessor;
import io.ib67.sfcraft.strategy.BackupStrategy;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static io.ib67.sfcraft.JustBackupMod.PERFORMING_BACKUP;

@Log4j2
public record BackupWorker(
        MinecraftServer server,
        BackupStrategy strategy,
        JustBackupConfig config
) implements Supplier<Backup> {

    @Override
    @SneakyThrows
    public Backup get() {
        if (server == null) {
            throw new IllegalStateException("MinecraftServer is not initialized yet, not doing backup");
        }
        int i = 0;
        while (!PERFORMING_BACKUP.compareAndSet(false, true)) {
            if (i++ % 10 == 0) log.info("Server is saving world while performing backup! waiting...");
            Thread.sleep(2000);
        }
        // performing backup is now true
        try {
            if (i == 0) {
                // not saved yet.
                server.saveAll(true, true, false);
            }
            var session = ((LevelStorageAccessor) server).getSession();
            var path = session.getDirectory().getRootPath();
            var backupName = "Backup_" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now());
            var backupParentRoot = Path.of(config.temporaryBackupDir());
            if (Files.notExists(backupParentRoot)) Files.createDirectories(backupParentRoot);
            var backupFile = backupParentRoot.resolve(backupName + ".jpack");
            log.info("Creating bundle {}", backupName);
            //todo diff
            Path worldDir = Path.of(path);
            new Bundle(new WorldDir(worldDir))
                    .compress(config.compress())
                    .allowGunzip(config.allowGunzip())
                    .buildBundle(backupFile);
            var backup = strategy.createBackup(worldDir, backupFile);
            Files.deleteIfExists(backupFile);
            return backup;
        } finally {
            PERFORMING_BACKUP.set(false);
        }
    }
}
