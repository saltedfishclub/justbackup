package io.ib67.sfcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.ib67.sfcraft.config.JustBackupConfig;
import io.ib67.sfcraft.config.StorageOption;
import io.ib67.sfcraft.config.serializer.StorageOptionSerializer;
import io.ib67.sfcraft.mixin.LevelStorageAccessor;
import io.ib67.sfcraft.mixin.MixinMinecraftServer;
import io.ib67.sfcraft.strategy.BackupStrategy;
import lombok.SneakyThrows;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class JustBackupMod implements ModInitializer {
    public static final String MOD_ID = "justbackup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final AtomicBoolean PERFORMING_BACKUP = new AtomicBoolean(false);
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .registerTypeAdapter(StorageOption.class, new StorageOptionSerializer(
                    Map.of("s3", StorageOption.S3.class,
                            "local", StorageOption.Local.class)
            ))
            .create();
    protected ScheduledExecutorService scheduledBackupExecutor;
    protected JustBackupConfig config;
    protected BackupStrategy strategy;
    protected volatile MinecraftServer server;

    @Override
    @SneakyThrows
    public void onInitialize() {
        LOGGER.info("Loading backup configuration");
        var configPath = Path.of("config").resolve("just_backup.json");
        if (Files.notExists(configPath.getParent())) {
            Files.createDirectories(configPath.getParent());
        }
        if (Files.notExists(configPath)) {
            saveConfig(configPath);
        }
        config = GSON.fromJson(Files.readString(configPath), JustBackupConfig.class);
        this.strategy = createStrategy();
        LOGGER.info("Configuration has been loaded. Using storage option: {}", config.option().type());
        scheduledBackupExecutor = Executors.newSingleThreadScheduledExecutor();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Launching background threads...");
            this.server = server;
            scheduledBackupExecutor.scheduleAtFixedRate(new BackupWorker(server, strategy),
                    config.backupIntervalMinutes(), config.backupIntervalMinutes(), TimeUnit.MINUTES);
        });
    }

    private BackupStrategy createStrategy() {
        switch (config.option()) {
            case StorageOption.Local local -> {

            }
        }
    }


    @SneakyThrows
    private void saveConfig(Path configPath) {
        LOGGER.info("Saving default configuration");
        var cfg = new JustBackupConfig(
                60,
                5,
                true,
                new StorageOption.Local("backups")
        );
        Files.writeString(configPath, GSON.toJson(cfg));
    }
}