package io.ib67.sfcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.ib67.sfcraft.Globals;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Holds the read side of {@link Globals#WORLD_IO_LOCK} around region file writes and flushes.
 * Multiple IOWorker threads (one per dimension and storage kind) may run concurrently under
 * the read lock; while the backup worker holds the write lock, all region IO parks here and
 * resumes untouched afterwards.
 */
@Mixin(RegionFileStorage.class)
public abstract class MixinRegionBasedStorage {
    @WrapMethod(method = "write")
    private void backup$guardWrite(ChunkPos pos, CompoundTag tag, Operation<Void> original) {
        long stamp = Globals.WORLD_IO_LOCK.readLock();
        try {
            original.call(pos, tag);
        } finally {
            Globals.WORLD_IO_LOCK.unlockRead(stamp);
        }
    }

    @WrapMethod(method = "flush")
    private void backup$guardFlush(Operation<Void> original) {
        long stamp = Globals.WORLD_IO_LOCK.readLock();
        try {
            original.call();
        } finally {
            Globals.WORLD_IO_LOCK.unlockRead(stamp);
        }
    }
}
