package io.ib67.sfcraft;

import io.ib67.sfcraft.mixin.LevelStorageAccessor;
import io.ib67.sfcraft.strategy.BackupStrategy;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

import static io.ib67.sfcraft.JustBackupMod.PERFORMING_BACKUP;

@Log4j2
public record BackupWorker(
        MinecraftServer server,
        BackupManager manager
) implements Runnable {

    @Override
    @SneakyThrows
    public void run() {
        if (server == null) {
            log.warn("MinecraftServer is not initialized yet, not doing backup");
            return;
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
            manager.performBackup();
            strategy.createBackup(new WorldDir(Path.of(path)));
        } finally {
            PERFORMING_BACKUP.set(false);
        }
    }
}
