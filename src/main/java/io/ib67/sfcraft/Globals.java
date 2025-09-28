package io.ib67.sfcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.ib67.sfcraft.config.StorageOption;
import io.ib67.sfcraft.config.serializer.StorageOptionSerializer;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class Globals {
    /**
     * for api users: DO NOT MUTATE THIS STATE IN WHATEVER CASE SINCE THIS IS CAUTIOUSLY ALIGNED WITH THE CURRENT THREAD MODEL
     * AND DOING SO WILL BREAK YOUR WORLD
     * This state indicates:
     * 1. A minecraft auto-save is in-progress.
     * 2. The backup is in-progress
     * 3. Some chunks are writing to disk
     */
    public static final AtomicReference<IOState> BACKUP_LOCK = new AtomicReference<>(IOState.IDLE);
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .registerTypeAdapter(StorageOption.class, new StorageOptionSerializer(
                    Map.of("s3", StorageOption.S3.class,
                            "local", StorageOption.Local.class)
            ))
            .create();
}
