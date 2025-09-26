package io.ib67.sfcraft.mixin;

import io.ib67.sfcraft.IOState;
import io.ib67.sfcraft.JustBackupMod;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import lombok.SneakyThrows;
import net.minecraft.world.storage.RegionBasedStorage;
import net.minecraft.world.storage.RegionFile;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
        var witness = JustBackupMod.BACKUP_LOCK.getAndSet(IOState.STORAGE_SYNC);
        if (witness == IOState.BACKUP) {
            // yield out and keep blocking.
            // The backup worker will wait for us setting this back to backup.
            JustBackupMod.BACKUP_LOCK.set(IOState.BACKUP);
            while (JustBackupMod.BACKUP_LOCK.getAcquire() == IOState.BACKUP) {
                Thread.sleep(2000);
            }
        }
        // program point: IDLE, AUTOSAVE, STORAGESYNC
        try {
            for (RegionFile value : this.cachedRegionFiles.values()) {
                value.sync();
            }
        } finally {
            // we asserts that there are no other threads calling sync()
            // so we can just jump back to the witness value.
            // witness: AUTO_SAVE or IDLE
            JustBackupMod.BACKUP_LOCK.compareAndExchange(IOState.STORAGE_SYNC, witness);
            JustBackupMod.BACKUP_LOCK.setRelease(witness);
        }
    }
}
