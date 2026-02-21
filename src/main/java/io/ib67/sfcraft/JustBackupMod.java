package io.ib67.sfcraft;

import com.github.luben.zstd.Zstd;
import com.google.gson.reflect.TypeToken;
import io.ib67.sfcraft.config.JustBackupConfig;
import io.ib67.sfcraft.config.StorageOption;
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
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class JustBackupMod implements ModInitializer {
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

    @Override
    @SneakyThrows
    public void onInitialize() {
        LOGGER.info("Loading backup configuration");
        Path config1 = Path.of("config");
        backupIndexPath = config1.resolve("just_backup_index.json");
        var configPath = config1.resolve("just_backup.json");
        if (Files.notExists(configPath.getParent()))
            Files.createDirectories(configPath.getParent());
        if (Files.notExists(configPath))
            saveConfig(configPath);
        if (Files.notExists(backupIndexPath))
            Files.createFile(backupIndexPath);

        config = Globals.GSON.fromJson(Files.readString(configPath), JustBackupConfig.class);
        config.backupSubjects().forEach((k, v) -> {
            var subjectPath = Path.of(v);
            if (Files.notExists(subjectPath)) {
                LOGGER.error("Backup _subject {} does not exist.", v);
                return;
            }
            backupSubjects.put(k, new BackupSubject(k, subjectPath, new ArrayList<>()));
        });
        if (config.zstdDictPath() != null && !config.zstdDictPath().isEmpty()) {
            zstdDict = Files.readAllBytes(Path.of(config.zstdDictPath()));
        }
        var strategy = createStrategy();
        this.tracker = new BackupTracker(strategy, Globals.GSON.fromJson(Files.readString(backupIndexPath), new TypeToken<>() {
        }));
        LOGGER.info("Configuration has been loaded. Using storage option: {}", config.option().type());
        scheduledBackupExecutor = Executors.newSingleThreadScheduledExecutor();
        registerWatchService();
        registerHandlers();
    }

    private void registerWatchService() {
        var watcherThread = new FileWatcherThread(
                config.backupSubjects().entrySet().stream()
                        .map(it -> Map.entry(it.getKey(), Path.of(it.getValue())))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
                this::handleFileChanges
        );
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            watcherThread.interrupt();
        });
        watcherThread.start();
    }

    private void handleFileChanges(String _subject, Path path) {
        synchronized (backupSubjects) {
            var subject = backupSubjects.get(_subject);
            if (subject != null)
                subject.changedFiles().add(path); // check null
        }
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
                            LOGGER.info("Backup success!");
                            it.forEach((k, v) -> LOGGER.info(k + ": " + v));
                        });
                    },
                    config.backupIntervalMinutes(), config.backupIntervalMinutes(), TimeUnit.MINUTES);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            try {
                Files.deleteIfExists(Path.of(config.temporaryBackupDir()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        var command = new BackupCommands(this);
        CommandRegistrationCallback.EVENT.register(command::registerCommand);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LOGGER.info("Shutting down the executor");
            scheduledBackupExecutor.shutdown();
            if (command.restoreIssued != null) {
                command.restoreIssued.run();
            }
            tracker.close();
        });
    }

    public CompletableFuture<Map<String, Backup>> backupAll(boolean incremental) {
        if (backupSubjects.isEmpty()) return CompletableFuture.completedFuture(Map.of());
        var map = new HashMap<String, CompletableFuture<Backup>>();
        server.getPlayerList().broadcastSystemMessage(
                Component.literal(" BACKUP >> ").withColor(Color.RED.getRGB()).withStyle(it -> it.withBold(true))
                        .append(Component.nullToEmpty("The server is performing a backup, you may experience some lag.")),
                false
        );
        for (var entry : backupSubjects.entrySet()) {
            map.put(entry.getKey(), issueBackup(entry.getKey(), incremental));
        }
        return CompletableFuture.allOf(map.values().toArray(new CompletableFuture[0]))
                .thenApply(it -> {
                    var _map = new HashMap<String, Backup>();
                    map.forEach((k, v) -> {
                        var result = v.join();
                        LOGGER.info("Backup information of {}: {}", k, result);
                        _map.put(k, result);
                    });
                    return _map;
                });
    }

    public CompletableFuture<Backup> issueBackup(String subjectName, boolean incremental) {
        var subject = backupSubjects.get(subjectName);
        if (subject == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown backup _subject: " + subjectName));
        if (!incremental) {
            try (var s = Files.walk(subject.root())) {
                subject = new BackupSubject(subject.name(), subject.root(), s.filter(Files::isRegularFile)
                        .filter(it -> !it.getFileName().toString().equals("session.lock"))
                        .toList());
            } catch (IOException e) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Cannot perform full backup for " + subjectName, e));
            }
        }
        var finalSubject = subject;
        if (config.allowGunzip()) LOGGER.info("Chunk reassembler is enabled!");
        var worker = new BackupWorker(server, tracker, Path.of(config.temporaryBackupDir()),
                subject, c ->
                c.allowGunzip(config.allowGunzip())
                        .relativeRoot(finalSubject.root())
                        .allocator(ByteBufAllocator.DEFAULT)
                        .maxWorkers(config.compressionWorkerThreads())
                        .compressionLevel(config.compressionLevel())
                        .dictionary(zstdDict)
        );
        return CompletableFuture.supplyAsync(worker, scheduledBackupExecutor).whenComplete(this::onBackupComplete);
    }

    @SneakyThrows
    private void onBackupComplete(@UnknownNullability Backup backup, @UnknownNullability Throwable throwable) {
        if (throwable != null) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("Backup failed. For administrators, please check your server console."), false);
            LOGGER.error(throwable.getMessage(), throwable);
        } else {
            Files.writeString(backupIndexPath, Globals.GSON.toJson(tracker.getTrackedBackups()));
            server.getPlayerList().broadcastSystemMessage(Component.literal("Backup success. " + backup), false);
        }
    }

    private BackupStrategy createStrategy() {
        return switch (config.option()) {
            case StorageOption.Local local -> new LocalBackupStrategy(local);
            case StorageOption.S3 s3 -> new S3BackupStrategy(s3, Path.of(config.temporaryBackupDir()));
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