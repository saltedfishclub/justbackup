package io.ib67.sfcraft;

import io.ib67.sfcraft.bundler.BundleWriter;
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
import java.util.List;
import java.util.function.Supplier;

import static io.ib67.sfcraft.JustBackupMod.BACKUP_LOCK;

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
        while (!BACKUP_LOCK.compareAndSet(IOState.IDLE, IOState.BACKUP)) {
            log.info("Server is saving world while performing backup! waiting...");
            i++;
            Thread.sleep(2000);
        }
        // performing backup is now true
        var backupName = "Backup_" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now());
        var backupParentRoot = Path.of(config.temporaryBackupDir());
        if (Files.notExists(backupParentRoot)) Files.createDirectories(backupParentRoot);
        var backupFile = backupParentRoot.resolve(backupName + ".jbp.zst");
        try {
            if (i == 0) {
                // not saved yet.
                server.saveAll(true, true, false);
            }
            var session = ((LevelStorageAccessor) server).getSession();
            var path = session.getDirectory().getRootPath();
            log.info("Creating bundle {}", backupName);
            //todo diff
            Path worldDir = Path.of(path);
            var tree = new WorldDir(worldDir);
            BundleWriter.createBundle(backupFile, tree.everything(), c -> c.allowGunzip(config.allowGunzip())
                    .relativeRoot(worldDir)
                    .compressionLevel(config.compressionLevel()));
            var backup = strategy.createBackup(worldDir, backupFile);
            Files.deleteIfExists(backupFile);
            return backup;
        } catch (Exception e) {
            Files.deleteIfExists(backupFile);
            throw e;
        } finally {
            // Though we won't access the state within backup progress, there are certain cases
            // that this state will be changed to STORAGE_SYNC.
            // Also take a look at MixinRegionBasedStorage.
            while (!BACKUP_LOCK.compareAndSet(IOState.BACKUP, IOState.IDLE)) {
                log.error("Expect BACKUP state but got {}", BACKUP_LOCK.get());
                Thread.sleep(2000);
            }
        }
    }
}
