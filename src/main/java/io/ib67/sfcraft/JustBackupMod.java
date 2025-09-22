package io.ib67.sfcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.ib67.sfcraft.config.JustBackupConfig;
import io.ib67.sfcraft.config.StorageOption;
import io.ib67.sfcraft.config.serializer.StorageOptionSerializer;
import io.ib67.sfcraft.mixin.LevelStorageAccessor;
import io.ib67.sfcraft.mixin.MixinMinecraftServer;
import io.ib67.sfcraft.strategy.BackupStrategy;
import io.ib67.sfcraft.strategy.BackupTracker;
import io.ib67.sfcraft.strategy.LocalBackupStrategy;
import io.ib67.sfcraft.strategy.S3BackupStrategy;
import lombok.SneakyThrows;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.jetbrains.annotations.UnknownNullability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class JustBackupMod implements ModInitializer {
    public static final String MOD_ID = "justbackup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /**
     * This state indicates:
     * 1. A minecraft auto-save is in-progress.
     * 2. The backup is in-progress
     */
    public static final AtomicBoolean PERFORMING_BACKUP = new AtomicBoolean(false);
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .registerTypeAdapter(StorageOption.class, new StorageOptionSerializer(
                    Map.of("s3", StorageOption.S3.class,
                            "local", StorageOption.Local.class)
            ))
            .create();
    protected ScheduledExecutorService scheduledBackupExecutor;
    protected JustBackupConfig config;
    protected BackupTracker tracker;
    protected Path backupIndexPath;
    protected volatile MinecraftServer server;
    protected volatile boolean suspend;

    @Override
    @SneakyThrows
    public void onInitialize() {
        LOGGER.info("Loading backup configuration");
        backupIndexPath = Path.of("config").resolve("just_backup_index.json");
        var configPath = Path.of("config").resolve("just_backup.json");
        if (Files.notExists(configPath.getParent()))
            Files.createDirectories(configPath.getParent());
        if (Files.notExists(configPath))
            saveConfig(configPath);
        if (Files.notExists(backupIndexPath))
            Files.createFile(backupIndexPath);

        config = GSON.fromJson(Files.readString(configPath), JustBackupConfig.class);
        var strategy = createStrategy();
        this.tracker = new BackupTracker(strategy, GSON.fromJson(Files.readString(backupIndexPath), new TypeToken<>() {
        }));
        LOGGER.info("Configuration has been loaded. Using storage option: {}", config.option().type());
        scheduledBackupExecutor = Executors.newSingleThreadScheduledExecutor();
        registerHandlers();
    }

    private void registerHandlers() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Launching background threads...");
            this.server = server;
            scheduledBackupExecutor.scheduleAtFixedRate(this::issueBackup,
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
            scheduledBackupExecutor.shutdown();
            if (command.restoreIssued != null) {
                command.restoreIssued.run();
            }
            tracker.close();
        });
    }

    public CompletableFuture<Backup> issueBackup() {
        if (suspend) {
            return CompletableFuture.failedFuture(new IllegalStateException("Backup is temporarily disabled"));
        }
        server.getPlayerManager().broadcast(Text.literal("The server will begin the backup shortly, you may experience some lag."), false);
        var worker = new BackupWorker(server, tracker, config);
        return CompletableFuture.supplyAsync(worker, scheduledBackupExecutor).whenComplete(this::onBackupComplete);
    }

    @SneakyThrows
    private void onBackupComplete(@UnknownNullability Backup backup, @UnknownNullability Throwable throwable) {
        if (throwable != null) {
            server.getPlayerManager().broadcast(Text.literal("Backup failed. For administrators, please check your server console."), false);
            throwable.printStackTrace();
        } else {
            Files.writeString(backupIndexPath, GSON.toJson(tracker.getTrackedBackups()));
            server.getPlayerManager().broadcast(Text.literal("Backup success. " + backup), false);
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
                60,
                5,
                true,
                true,
                "./.backup_cache",
                new StorageOption.Local("backups", 0)
        );
        var path = Path.of(cfg.temporaryBackupDir());
        if (Files.notExists(path)) {
            Files.createDirectories(path);
        }
        Files.writeString(configPath, GSON.toJson(cfg));
    }
}