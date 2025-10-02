package io.ib67.sfcraft.mixin;

import io.ib67.sfcraft.Globals;
import io.ib67.sfcraft.IOState;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import lombok.SneakyThrows;
import net.minecraft.world.storage.RegionBasedStorage;
import net.minecraft.world.storage.RegionFile;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RegionBasedStorage.class)
public abstract class MixinRegionBasedStorage {
    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<RegionFile> cachedRegionFiles;

    /**
     * @author iceBear67
     * @reason to wrap the saving process with an exception handler.
     */
    @Overwrite
    @SneakyThrows
    public void sync() {
        // allowed states:
        // STORAGE_SYNC, AUTO_SAVE, IDLE
        // we try
        var witness = Globals.BACKUP_LOCK.getAndSet(IOState.STORAGE_SYNC);
        if (witness == IOState.BACKUP) {
            // yield out and keep blocking.
            // The backup worker will wait for us setting this back to backup.
            int i =0;
            Globals.BACKUP_LOCK.set(IOState.BACKUP);
            while (Globals.BACKUP_LOCK.getAcquire() == IOState.BACKUP) {
                if(i==0){
                    System.out.println("Server is performing backup, ChunkIO will wait for it.");
                }
                if(i++ > 3000){
                    System.err.println("WARNING: BACKUP_LOCK is holding for 10 minutes!");
                    System.err.println("ChunkIO will not wait for backup anymore, writting to disk...");
                    System.err.println("You may adjust this behaviour in configuration");
                    break;
                }
                Thread.sleep(2000);
            }
        }
        // program point: IDLE, AUTOSAVE, STORAGESYNC
        try {
            for (RegionFile value : this.cachedRegionFiles.values()) {
                value.sync();
            }
        } finally {
            // we assert that there are no other threads calling sync()
            // so we can just jump back to the witness value.
            // witness: AUTO_SAVE or IDLE
            Globals.BACKUP_LOCK.compareAndExchange(IOState.STORAGE_SYNC, witness);
            Globals.BACKUP_LOCK.setRelease(witness);
        }
    }
}
