package io.ib67.sfcraft.strategy;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import io.ib67.sfcraft.Backup;
import io.ib67.sfcraft.WorldDir;
import io.ib67.sfcraft.bundler.BundlerEntry;
import io.ib67.sfcraft.bundler.EntryOutputStream;
import io.ib67.sfcraft.bundler.SerialBundlerReader;
import io.ib67.sfcraft.bundler.SerialBundlerWriter;
import io.ib67.sfcraft.config.StorageOption;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Log4j2
@RequiredArgsConstructor
public class LocalBackupStrategy implements BackupStrategy {
    protected final StorageOption.Local option;
    protected final Path backupParentRoot = Path.of(option.saveDir());
    protected final boolean allowGunzip;

    @Override
    @SneakyThrows
    public Backup createBackup(WorldDir dir, boolean compress) {
        var sortedEntries = dir.everything().stream().sorted((a, b) -> {
            boolean aMatch = a.endsWith(".mca") || a.endsWith(".dat");
            boolean bMatch = b.endsWith(".mca") || b.endsWith(".dat");

            if (aMatch && !bMatch) return -1;
            if (!aMatch && bMatch) return 1;
            return a.compareTo(b);
        }).map(dir.root()::relativize).toList();
        var backupName = "Backup_" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now());
        var backupFile = backupParentRoot.resolve(backupName + ".jbp");
        SerialBundlerWriter writer;
        if (compress) {
            var out = new ZstdOutputStreamNoFinalizer(Files.newOutputStream(backupFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE));
            writer = new SerialBundlerWriter(out);
        } else {
            var chan = FileChannel.open(backupFile);
            writer = new SerialBundlerWriter(chan, 0);
        }
        writeEntries(sortedEntries, writer, allowGunzip && compress);
        writer.close();

        try (var backup = FileChannel.open(backupFile);
             var bos = new ByteArrayOutputStream();
             var metadata = new EntryOutputStream(new ZstdOutputStreamNoFinalizer(bos))) {
            metadata.writeMagic();
            for (BundlerEntry entry : writer.getEntries()) {
                metadata.write(entry);
            }
            var result = bos.toByteArray();
            var buffer = ByteBuffer.allocate(result.length + 4);
            buffer.put(result);
            buffer.putLong(result.length);
            backup.write(buffer);
        }
        return new Backup(backupName, backupFile.getFileName().toString(), false, Files.size(backupFile));
    }

    private void writeEntries(
            List<Path> sortedEntries,
            SerialBundlerWriter writer,
            boolean allowGunzip) throws IOException {
        for (Path sortedEntry : sortedEntries) {
            var fileName = sortedEntry.getFileName();
            var shouldGunzip = allowGunzip && (fileName.endsWith("mca") || fileName.endsWith("dat"));
            if (shouldGunzip) {
                writer.writeGunzip(sortedEntry, sortedEntry.toString());
            } else {
                writer.writePlain(sortedEntry, sortedEntry.toString());
            }
        }
    }


    @Override
    @SneakyThrows
    public void recoverBackup(Backup backup, Path restorePath) {
        // check if it is compressed
        var backupFile = backupParentRoot.resolve(backup.backupKey());
        if (Files.notExists(backupFile)) throw new IllegalArgumentException("Backup " + backupFile + " does not exist");
        try (var bundlerReader = new SerialBundlerReader(backupFile)) {
            while (bundlerReader.hasEntry()) {
                var entry = bundlerReader.readEntry();
                var target = restorePath.resolve(entry.fileName());
                bundlerReader.writeTo(FileChannel.open(target), entry);
            }
        }
    }
}
