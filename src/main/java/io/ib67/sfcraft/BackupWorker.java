package io.ib67.sfcraft;

import io.ib67.sfcraft.bundler.BundleWriter;
import io.ib67.sfcraft.strategy.BackupStrategy;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static io.ib67.sfcraft.Globals.BACKUP_LOCK;

@Log4j2
public record BackupWorker(
        MinecraftServer server,
        BackupStrategy strategy,
        Path tmpDir,
        BackupSubject subject,
        UnaryOperator<BundleWriter.BundleWriterBuilder> config
) implements Supplier<Backup> {

    @SneakyThrows
    public BackupWorker {
        if (Files.notExists(tmpDir)) Files.createDirectories(tmpDir);
    }

    @Override
    @SneakyThrows
    public Backup get() {
        if (server == null) {
            throw new IllegalStateException("MinecraftServer is not initialized yet, not doing backup");
        }
        server.saveEverything(true, true, false);
        while (!BACKUP_LOCK.compareAndSet(IOState.IDLE, IOState.BACKUP)) {
            log.info("Server is saving world while performing backup! waiting...");
            Thread.sleep(2000);
        }
        // performing backup is now true
        var tmpBundle = tmpDir.resolve("backup_" + subject.name() + "_" + System.currentTimeMillis() + ".swb.zst");
        try {
            // tmp file path
            log.info("Creating bundle {}", tmpBundle);
            BundleWriter.createBundle(tmpBundle, subject.changedFiles(), config);
        } catch (Exception e) {
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
        var result = strategy.createBackup(subject.name(), subject.root(), tmpBundle);
        Files.deleteIfExists(tmpBundle);
        return result;
    }
}
