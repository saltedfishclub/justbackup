package io.ib67.sfcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.ib67.sfcraft.config.StorageOption;
import io.ib67.sfcraft.config.serializer.StorageOptionSerializer;

import java.util.Map;
import java.util.concurrent.locks.StampedLock;

public class Globals {
    /**
     * Guards on-disk world files during backups.
     * <p>
     * Chunk IO ({@code RegionFileStorage.write/flush}, running on the per-dimension IOWorker
     * threads) holds the read lock; the backup worker holds the write lock while it snapshots
     * files. The main thread never blocks on this lock: {@code autoSave} and manual
     * {@code saveEverything} calls are skipped while the write lock is held (see
     * {@code MixinMinecraftServer}), which is what makes it safe for the backup thread to
     * wait on main-thread tasks while holding the write lock.
     * <p>
     * A {@link StampedLock} is used instead of a ReentrantReadWriteLock because the write
     * stamp may be released from a different code path than the one that acquired it
     * (early release once files are cloned to staging). None of the guarded sections are
     * reentrant.
     */
    public static final StampedLock WORLD_IO_LOCK = new StampedLock();

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .registerTypeAdapter(StorageOption.class, new StorageOptionSerializer(
                    Map.of("s3", StorageOption.S3.class,
                            "local", StorageOption.Local.class)
            ))
            .create();
}
