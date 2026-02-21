package io.ib67.sfcraft;

import net.minecraft.network.chat.Component;

public record Backup(
        String name,
        String backupKey,
        String type,
        String from,
        boolean incremental,
        long sizeTotal
) {
    public Component toText(){
        return Component.nullToEmpty("sizeTotal: "+Math.round(((sizeTotal / 1024.0) / 1024.0) * 100) * 0.01 + "MiB, "+
                (incremental ? "incremental" :"") + ", type: "+type+"."
        );
    }
}
