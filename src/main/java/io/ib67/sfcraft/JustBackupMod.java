package io.ib67.sfcraft;

import com.github.luben.zstd.Zstd;
import com.google.gson.reflect.TypeToken;
import io.ib67.sfcraft.config.JustBackupConfig;
import io.ib67.sfcraft.config.StorageOption;
import io.ib67.sfcraft.strategy.BackupChains;
import io.ib67.sfcraft.strategy.BackupStrategy;
import io.ib67.sfcraft.strategy.BackupTracker;
import io.ib67.sfcraft.strategy.LocalBackupStrategy;
import io.ib67.sfcraft.strategy.S3BackupStrategy;
import io.netty.buffer.ByteBufAllocator;
import lombok.SneakyThrows;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.UnknownNullability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JustBackupMod implements ModInitializer, FileWatcherThread.Listener {
    public static final String MOD_ID = "justbackup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    protected final Map<String, BackupSubject> backupSubjects = new HashMap<>();
    protected int lastBackupServerTicks;
    protected volatile MinecraftServer server;
    protected volatile boolean suspend;
    protected byte[] zstdDict;
    protected ScheduledExecutorService scheduledBackupExecutor;
    protected JustBackupConfig config;
    protected BackupTracker tracker;
    protected Path backupIndexPath;
    protected Path tmpDir;
    protected List<Path> excludedRoots;
    protected FileWatcherThread watcherThread;

    @Override
    public void onInitialize() {
        try {
            doInitialize();
        } catch (Exception e) {
            LOGGER.error("Error while initializing the mod", e);
            LOGGER.error("This mod will not work w/o correct configuration.");
            if (watcherThread != null) {
                if (watcherThread.getState() != Thread.State.NEW) watcherThread.interrupt();
            }
        }
    }

    @SneakyThrows
    private void doInitialize() {
        LOGGER.info("Loading backup configuration");
        Path configDir = Path.of("config");
        backupIndexPath = configDir.resolve("just_backup_index.json");
        var configPath = configDir.resolve("just_backup.json");
        if (Files.notExists(configPath.getParent()))
            Files.createDirectories(configPath.getParent());
        if (Files.notExists(configPath))
            saveConfig(configPath);

        config = Globals.GSON.fromJson(Files.readString(configPath), JustBackupConfig.class);
        config.backupSubjects().forEach((k, v) -> {
            var subjectPath = Path.of(v);
            if (Files.notExists(subjectPath)) {
                LOGGER.error("Backup subject {} does not exist.", v);
                return;
            }
            backupSubjects.put(k, new BackupSubject(k, subjectPath));
        });
        if (config.zstdDictPath() != null && !config.zstdDictPath().isEmpty()) {
            zstdDict = Files.readAllBytes(Path.of(config.zstdDictPath()));
        }
        tmpDir = Path.of(config.temporaryBackupDir());
        excludedRoots = new ArrayList<>();
        excludedRoots.add(tmpDir);
        if (config.option() instanceof StorageOption.Local local) {
            excludedRoots.add(Path.of(local.saveDir()));
        }

        var strategy = createStrategy();
        Map<String, Backup> initialIndex = Files.exists(backupIndexPath)
                ? Globals.GSON.fromJson(Files.readString(backupIndexPath), new TypeToken<Map<String, Backup>>() {
        })
                : null;
        this.tracker = new BackupTracker(strategy, initialIndex, backupIndexPath);
        LOGGER.info("Configuration has been loaded. Using storage option: {}", config.option().type());
        scheduledBackupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "JustBackup/Worker");
            t.setDaemon(false);
            return t;
        });
        registerWatchService();
        registerHandlers();
    }

    private void registerWatchService() {
        watcherThread = new FileWatcherThread(
                config.backupSubjects().entrySet().stream()
                        .map(it -> Map.entry(it.getKey(), Path.of(it.getValue())))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
                this
        );
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (watcherThread.getState() != Thread.State.TERMINATED) watcherThread.interrupt();
        }));
        watcherThread.start();
    }

    @Override
    public void onChange(String subjectName, Path file) {
        var subject = backupSubjects.get(subjectName);
        if (subject != null) subject.addChanged(file);
    }

    @Override
    public void onDelete(String subjectName, Path file) {
        var subject = backupSubjects.get(subjectName);
        if (subject != null) subject.addDeleted(file);
    }

    @Override
    public void onOverflow(String subjectName) {
        var subject = backupSubjects.get(subjectName);
        if (subject != null) subject.forceFullNext();
    }

    @SneakyThrows
    private void registerHandlers() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Launching background threads...");
            this.server = server;
            if (config.bundleFullAtStartup()) {
                backupAll(false);
            }
            scheduledBackupExecutor.scheduleAtFixedRate(() -> {
                        if (lastBackupServerTicks > 0 && lastBackupServerTicks == server.getTickCount()) {
                            // the server is idling
                            LOGGER.debug("Server is idling, not backing up.");
                            return;
                        }
                        lastBackupServerTicks = server.getTickCount();
                        if (!suspend) backupAll(config.incremental()).thenAccept(it -> {
                            LOGGER.info("Scheduled backup finished.");
                            it.forEach((k, v) -> LOGGER.info("{}: {}", k, v));
                        });
                    },
                    config.backupIntervalMinutes(), config.backupIntervalMinutes(), TimeUnit.MINUTES);
        });
        var command = new BackupCommands(this);
        CommandRegistrationCallback.EVENT.register(command::registerCommand);
        // Runs at the head of stopServer, before the final world save: in-flight backups must
        // release the world IO lock before that save, or the flush would park forever.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            suspend = true;
            scheduledBackupExecutor.shutdown();
            try {
                if (!scheduledBackupExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                    LOGGER.info("Waiting up to 10 minutes for the in-flight backup to finish...");
                    if (!scheduledBackupExecutor.awaitTermination(10, TimeUnit.MINUTES)) {
                        LOGGER.error("In-flight backup did not finish in time; its bundle may be incomplete.");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (command.restoreIssued != null) {
                try {
                    command.restoreIssued.run();
                } catch (Exception e) {
                    LOGGER.error("Backup restoration failed", e);
                }
            }
            try {
                tracker.close();
            } catch (Exception e) {
                LOGGER.error("Error while closing the backup storage", e);
            }
            cleanTemporaryDir();
            if (watcherThread != null && watcherThread.getState() != Thread.State.TERMINATED) {
                watcherThread.interrupt();
            }
        });
    }

    /**
     * Removes only artifacts we created (staging clones, leftover bundles, temp downloads).
     * Never deletes the directory itself: a misconfigured temporaryBackupDir pointing at the
     * server root must not wipe the server.
     */
    private void cleanTemporaryDir() {
        try {
            FsUtil.deleteRecursively(tmpDir.resolve("staging"));
        } catch (IOException e) {
            LOGGER.warn("Cannot clean staging dir", e);
        }
        if (Files.notExists(tmpDir)) return;
        try (Stream<Path> files = Files.list(tmpDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> {
                        var n = p.getFileName().toString();
                        return (n.startsWith("backup_") && n.endsWith(".swb.zst")) || n.startsWith("s3_");
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            LOGGER.warn("Cannot delete leftover {}", p, e);
                        }
                    });
        } catch (IOException e) {
            LOGGER.warn("Cannot clean temporary backup dir", e);
        }
    }

    /**
     * Logs the message and, when enabled and a server is up, broadcasts it to players from
     * the main thread (PlayerList is not safe to touch from the backup executor).
     */
    public void broadcast(Component message) {
        LOGGER.info(message.getString());
        if (!config.broadcast()) return;
        var s = server;
        if (s == null || !s.isRunning()) return;
        s.submit(() -> s.getPlayerList().broadcastSystemMessage(message, false));
    }

    public CompletableFuture<Map<String, Backup>> backupAll(boolean incremental) {
        if (backupSubjects.isEmpty()) return CompletableFuture.completedFuture(Map.of());
        var map = new HashMap<String, CompletableFuture<Backup>>();
        broadcast(Component.literal(" BACKUP >> ").withColor(Color.RED.getRGB()).withStyle(it -> it.withBold(true))
                .append(Component.nullToEmpty("The server is performing a backup, you may experience some lag.")));
        for (var entry : backupSubjects.entrySet()) {
            map.put(entry.getKey(), issueBackup(entry.getKey(), incremental));
        }
        return CompletableFuture.allOf(map.values().toArray(new CompletableFuture[0]))
                .thenApply(it -> {
                    var results = new HashMap<String, Backup>();
                    map.forEach((k, v) -> {
                        var result = v.join();
                        LOGGER.info("Backup information of {}: {}", k, result);
                        results.put(k, result);
                    });
                    return results;
                });
    }

    public CompletableFuture<Backup> issueBackup(String subjectName, boolean incremental) {
        var subject = backupSubjects.get(subjectName);
        if (subject == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown backup subject: " + subjectName));
        if (config.allowGunzip()) LOGGER.info("Chunk reassembler is enabled!");
        var worker = new BackupWorker(server, tracker, tmpDir, subject, incremental, excludedRoots, c ->
                c.allowGunzip(config.allowGunzip())
                        .allocator(ByteBufAllocator.DEFAULT)
                        .maxWorkers(config.compressionWorkerThreads())
                        .compressionLevel(config.compressionLevel())
                        .dictionary(zstdDict)
        );
        return CompletableFuture.supplyAsync(() -> {
            if (!tracker.isAvailable()) {
                throw new IllegalStateException("Backup storage is not available (quota exceeded?), skipping backup of " + subjectName);
            }
            return worker.get();
        }, scheduledBackupExecutor).whenComplete(this::onBackupComplete);
    }

    private void onBackupComplete(@UnknownNullability Backup backup, @UnknownNullability Throwable throwable) {
        if (throwable != null) {
            broadcast(Component.literal("Backup failed. For administrators, please check your server console."));
            LOGGER.error("Backup failed", throwable);
        } else {
            broadcast(Component.literal("Backup success. ").append(backup.toText()));
            rotate(backup.subject());
        }
    }

    /**
     * Deletes whole generations (a full backup plus its incrementals) beyond the newest
     * {@code keepBackups} ones. Entries without chain metadata are never rotated away.
     */
    private void rotate(String subjectName) {
        if (subjectName == null) return;
        try {
            var victims = BackupChains.rotationVictims(
                    tracker.getTrackedBackups().values(), subjectName, config.keepBackups());
            for (var victim : victims) {
                LOGGER.info("Rotating out old backup {} ({})", victim.backupKey(), victim.incremental() ? "INCR" : "FULL");
                try {
                    tracker.deleteBackup(victim);
                } catch (Exception e) {
                    LOGGER.warn("Cannot delete old backup {}", victim.backupKey(), e);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Backup rotation failed for {}", subjectName, e);
        }
    }

    private BackupStrategy createStrategy() {
        return switch (config.option()) {
            case StorageOption.Local local -> new LocalBackupStrategy(local);
            case StorageOption.S3 s3 -> new S3BackupStrategy(s3, tmpDir);
        };
    }

    @SneakyThrows
    private void saveConfig(Path configPath) {
        LOGGER.info("Saving default configuration");
        var cfg = new JustBackupConfig(
                true,
                true,
                true,
                60,
                5,
                Zstd.defaultCompressionLevel(),
                4,
                true,
                "./.backup_cache",
                null, null,
                new StorageOption.Local("backups", 0)
        );
        var path = Path.of(cfg.temporaryBackupDir());
        if (Files.notExists(path)) {
            Files.createDirectories(path);
        }
        Files.writeString(configPath, Globals.GSON.toJson(cfg));
    }
}
