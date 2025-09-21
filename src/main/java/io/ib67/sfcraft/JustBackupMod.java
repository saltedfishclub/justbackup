package io.ib67.sfcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.ib67.sfcraft.config.JustBackupConfig;
import io.ib67.sfcraft.config.StorageOption;
import io.ib67.sfcraft.config.serializer.StorageOptionSerializer;
import io.ib67.sfcraft.mixin.LevelStorageAccessor;
import io.ib67.sfcraft.mixin.MixinMinecraftServer;
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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .registerTypeAdapter(StorageOption.class, new StorageOptionSerializer(
                    Map.of("s3", StorageOption.S3.class,
                            "local", StorageOption.Local.class)
            ))
            .create();
    protected ScheduledExecutorService scheduledBackupExecutor;
    protected JustBackupConfig config;
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
        LOGGER.info("Configuration has been loaded. Using storage option: {}", config.option().type());
        scheduledBackupExecutor = Executors.newSingleThreadScheduledExecutor();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Launching background threads...");
            this.server = server;
            scheduledBackupExecutor.scheduleAtFixedRate(this::performBackup,
                    config.backupIntervalMinutes(), config.backupIntervalMinutes(), TimeUnit.MINUTES);
        });
    }

    @SneakyThrows
    private void performBackup() {
        if (server == null) {
            LOGGER.warn("MinecraftServer is not initialized yet, not doing backup");
            return;
        }
        int i = 0;
        while (!PERFORMING_BACKUP.compareAndSet(false, true)) {
            if (i++ % 10 == 0) LOGGER.info("Server is saving world while performing backup! waiting...");
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
            // !compress
            if (!config.compress()) {
                performBackupUncompressed(new WorldDir(Path.of(path)));
            }
        } finally {
            PERFORMING_BACKUP.set(false);
        }
    }

    @SneakyThrows
    private void performBackupUncompressed(WorldDir worldDir) {
        var everything = worldDir.everything();
        var opt = (StorageOption.Local) config.option();
        var dst = Path.of(opt.saveDir()).resolve("Backup_" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now()));
        Files.createDirectories(dst);
        try(var f = Files.walk(worldDir.region())){
            bundleUncompressed(dst.resolve("regions.gz"), f.toList());
        }
        try(var f = Files.walk(worldDir.poi())) {
            bundleUncompressed(dst.resolve("poi.gz"), f.toList());
        }
        var other = dst.resolve("other.zip", )
    }

    @SneakyThrows
    private static void bundleUncompressed(Path dst, Collection<Path> toBundle){
        try(var fileOut = new RandomAccessFile(dst.toFile(), "rw");){
            var fileOutDst = fileOut.getChannel();
            for (Path path : toBundle) {
                var file = path.toFile();
                try(var in = new FileInputStream(path.toFile())){
                    in.getChannel().transferTo(0, file.length(), fileOutDst);
                }
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