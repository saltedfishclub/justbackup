package io.ib67.sfcraft.mixin;

import io.ib67.sfcraft.JustBackupMod;
import lombok.Getter;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.security.auth.callback.Callback;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer {

    @Shadow private int ticksUntilAutosave;

    @Shadow protected abstract int getAutosaveInterval();

    @Inject(method = "runAutosave", at = @At("HEAD"), cancellable = true)
    private void backup$checkSaving(CallbackInfo ci) {
        // is already backing up
        this.ticksUntilAutosave = getAutosaveInterval();
        if (!JustBackupMod.PERFORMING_BACKUP.compareAndSet(false, true)) ci.cancel();
    }
    @Inject(method="runAutosave", at=@At("RETURN"))
    private void backup$disableProtect(CallbackInfo ci){
        JustBackupMod.PERFORMING_BACKUP.set(false);
    }
}
