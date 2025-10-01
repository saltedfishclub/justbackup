package io.ib67.sfcraft;

import net.minecraft.text.Text;

public record Backup(
        String name,
        String backupKey,
        String type,
        String from,
        boolean incremental,
        long sizeTotal
) {
    public Text toText(){
        return Text.of("sizeTotal: "+Math.round(((sizeTotal / 1024.0) / 1024.0) * 100) * 0.01 + "MiB, "+
                (incremental ? "incremental" :"") + ", type: "+type+"."
        );
    }
}
