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
        return Text.of("sizeTotal: "+((sizeTotal / 1024.0) / 1024.0) + "GiB, "+
                (incremental ? "incremental" :"") + ", type: "+type+"."
        );
    }
}
