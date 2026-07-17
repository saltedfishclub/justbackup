package io.ib67.sfcraft.mixin;

import io.ib67.sfcraft.Globals;
import io.ib67.sfcraft.JustBackupMod;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skips main-thread saves while a backup holds the write lock. The main thread must never
 * block on {@link Globals#WORLD_IO_LOCK}: a save request would join the IOWorkers, which
 * are parked on the read lock, wedging the main thread until the backup finishes (and
 * tripping the watchdog). The world was saved right before the backup acquired the lock,
 * so skipping loses nothing.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer {

    @Shadow
    private int ticksUntilAutosave;

    @Shadow
    protected abstract int computeNextAutosaveInterval();

    @Inject(method = "autoSave", at = @At("HEAD"), cancellable = true)
    private void backup$skipAutoSaveDuringBackup(CallbackInfo ci) {
        if (Globals.WORLD_IO_LOCK.isWriteLocked()) {
            // vanilla resets this at the head of autoSave; replicate it on the cancel path
            // so the autosave doesn't re-trigger every tick
            this.ticksUntilAutosave = computeNextAutosaveInterval();
            JustBackupMod.LOGGER.info("Backup in progress, skipping this auto-save.");
            ci.cancel();
        }
    }

    @Inject(method = "saveEverything", at = @At("HEAD"), cancellable = true)
    private void backup$skipManualSaveDuringBackup(boolean suppressLog, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (Globals.WORLD_IO_LOCK.isWriteLocked()) {
            JustBackupMod.LOGGER.warn("Backup in progress, skipping this save request. The world was already saved when the backup started.");
            cir.setReturnValue(false);
        }
    }
}
