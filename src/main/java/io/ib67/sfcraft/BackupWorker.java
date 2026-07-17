package io.ib67.sfcraft;

import io.ib67.sfcraft.bundler.BundleWriter;
import io.ib67.sfcraft.strategy.BackupStrategy;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static io.ib67.sfcraft.Globals.WORLD_IO_LOCK;

/**
 * Runs on the backup executor thread. Flow:
 * <ol>
 *   <li>Save the world on the main thread (saveEverything is not thread-safe anywhere else)
 *       with flush=true, draining the IOWorkers so region files are complete on disk.</li>
 *   <li>Acquire the write side of {@link Globals#WORLD_IO_LOCK}, parking all region IO.</li>
 *   <li>Reflink-clone the files to a staging dir and release the lock early (CoW
 *       filesystems), or keep holding it while packing (fallback).</li>
 *   <li>Hand the bundle to the storage strategy.</li>
 * </ol>
 * The main thread never blocks on the lock — autoSave/saveEverything are skipped while it is
 * write-locked (see MixinMinecraftServer) — so waiting for main-thread tasks while holding
 * the write lock cannot deadlock.
 */
@Log4j2
public record BackupWorker(
        MinecraftServer server,
        BackupStrategy strategy,
        Path tmpDir,
        BackupSubject subject,
        boolean incremental,
        List<Path> excludedRoots,
        UnaryOperator<BundleWriter.BundleWriterBuilder> config
) implements Supplier<Backup> {
    private static final long LOCK_TIMEOUT_SECONDS = 60;

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
        var createdAt = System.currentTimeMillis();
        var full = !incremental || subject.consumeForceFull();

        runOnMainThread(() -> server.saveEverything(true, true, false), "world save");

        long stamp = WORLD_IO_LOCK.tryWriteLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (stamp == 0) {
            throw new IllegalStateException("World IO is still busy after " + LOCK_TIMEOUT_SECONDS
                    + "s, aborting backup of " + subject.name());
        }

        var tmpBundle = tmpDir.resolve("backup_" + subject.name() + "_" + createdAt + ".swb.zst");
        Set<Path> drained = null;
        Path stagingRoot = null;
        try {
            // Barrier: a main-thread save that started before we locked has finished by now.
            runOnMainThread(() -> {
            }, "main-thread barrier");

            List<Path> files;
            if (full) {
                files = collectFullFileList();
            } else {
                drained = subject.drainChanged();
                files = drained.stream().filter(this::eligible).toList();
            }

            var packRoot = subject.root();
            stagingRoot = ReflinkCloner.tryStage(subject.name(), packRoot, files,
                    tmpDir.resolve("staging").resolve(subject.name() + "_" + createdAt));
            if (stagingRoot != null) {
                var absRoot = packRoot.toAbsolutePath().normalize();
                var absStaging = stagingRoot;
                files = files.stream()
                        .map(f -> absStaging.resolve(absRoot.relativize(f.toAbsolutePath().normalize())))
                        .toList();
                packRoot = stagingRoot;
                WORLD_IO_LOCK.unlockWrite(stamp);
                stamp = 0;
                log.info("Reflinked {} files to staging; world IO resumed, packing without the lock.", files.size());
            }

            log.info("Creating {} bundle {} ({} files)", full ? "full" : "incremental", tmpBundle, files.size());
            var finalPackRoot = packRoot;
            BundleWriter.createBundle(tmpBundle, files, c -> config.apply(c).relativeRoot(finalPackRoot));
        } catch (Exception e) {
            if (drained != null) subject.mergeBack(drained);
            Files.deleteIfExists(tmpBundle);
            throw e;
        } finally {
            if (stamp != 0) WORLD_IO_LOCK.unlockWrite(stamp);
            if (stagingRoot != null) {
                try {
                    FsUtil.deleteRecursively(stagingRoot);
                } catch (IOException e) {
                    log.warn("Cannot clean up staging dir {}", stagingRoot, e);
                }
            }
        }

        try {
            return strategy.createBackup(subject.name(), subject.root(), tmpBundle, !full, createdAt);
        } catch (Exception e) {
            if (drained != null) subject.mergeBack(drained);
            throw e;
        } finally {
            Files.deleteIfExists(tmpBundle);
        }
    }

    /**
     * Runs a task on the main thread, but never hangs: once the server leaves its tick loop
     * (shutdown) submitted tasks are no longer processed, so we poll and abort the backup
     * instead of blocking forever.
     */
    private void runOnMainThread(Runnable task, String what) {
        var future = server.submit(task);
        while (true) {
            try {
                future.get(1, TimeUnit.SECONDS);
                return;
            } catch (TimeoutException e) {
                if (!server.isRunning()) {
                    future.cancel(false);
                    throw new IllegalStateException("Server is shutting down, aborting backup while waiting for " + what);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + what);
            } catch (ExecutionException e) {
                throw new IllegalStateException(what + " failed", e.getCause());
            }
        }
    }

    @SneakyThrows
    private List<Path> collectFullFileList() {
        try (Stream<Path> s = Files.walk(subject.root())) {
            return s.filter(Files::isRegularFile)
                    .filter(this::eligible)
                    .toList();
        }
    }

    private boolean eligible(Path path) {
        if (path.getFileName().toString().equals("session.lock")) return false;
        var abs = path.toAbsolutePath().normalize();
        for (Path excluded : excludedRoots) {
            if (abs.startsWith(excluded.toAbsolutePath().normalize())) return false;
        }
        return true;
    }
}
