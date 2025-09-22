package io.ib67.sfcraft.bundler;

import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import io.ib67.sfcraft.WorldDir;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.Validate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

@Accessors(fluent = true)
@Data
public class Bundle {
    public static final int MAGIC = 0xBACEBACE;
    public static final int COMPRESSED_MAGIC = 0xBACEAAAA;
    protected final WorldDir dir;
    protected boolean compress = true;
    protected boolean allowGunzip = true;
    protected boolean allowParallel = false;


    public Bundle(WorldDir dir) {
        this.dir = dir;
    }

    @SneakyThrows
    public static void unbundleFile(Path bundle, Path out) {
        try (var bundlerReader = new SerialBundlerReader(bundle)) {
            while (bundlerReader.hasEntry()) {
                var entry = bundlerReader.readEntry();
                var target = out.resolve(entry.fileName());
                Files.createDirectories(target.getParent());
                bundlerReader.writeTo(FileChannel.open(target, StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE_NEW), entry);
            }
        }
    }

    @SneakyThrows
    public void buildBundle(Path backupFile) {
        checkCondition();
        var sortedEntries = dir.everything().stream().sorted((a, b) -> {
            boolean aMatch = a.endsWith(".mca") || a.endsWith(".dat");
            boolean bMatch = b.endsWith(".mca") || b.endsWith(".dat");

            if (aMatch && !bMatch) return -1;
            if (!aMatch && bMatch) return 1;
            return a.compareTo(b);
        }).map(dir.root()::relativize).toList();
        if (Files.notExists(backupFile.getParent()))
            Files.createDirectories(backupFile.getParent());
        SerialBundlerWriter writer;
        if (compress) {
            var fout = Files.newOutputStream(backupFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            writeMagic(fout, null, true);
            var out = new ZstdOutputStreamNoFinalizer(fout);
            writer = new SerialBundlerWriter(out);
        } else {
            var chan = FileChannel.open(backupFile);
            writer = new SerialBundlerWriter(chan, 0);
            writeMagic(null, chan, false);
        }
        // todo parallelism
        writeEntries(sortedEntries, writer, allowGunzip && compress);
        writer.close();

        try (var backup = FileChannel.open(backupFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             var bos = new ByteArrayOutputStream();
             var metadata = new EntryOutputStream(new ZstdOutputStreamNoFinalizer(bos))) {
            var entries = writer.getEntries();
            for (BundlerEntry entry : entries) {
                metadata.write(entry);
            }
            metadata.flush();
            var result = bos.toByteArray();
            var buffer = ByteBuffer.allocate(result.length +4 + 4);
            buffer.put(result);
            buffer.putInt(entries.size());
            buffer.putInt(result.length);
            writeFully(backup, buffer);
        }
    }


    private static void writeMagic(OutputStream out, FileChannel channel, boolean compress) throws IOException {
        var magic = compress ? Bundle.COMPRESSED_MAGIC : Bundle.MAGIC;
        if (channel != null) {
            channel.write(ByteBuffer.allocate(4).putInt(magic).flip());
        } else {
            out.write(ByteBuffer.allocate(4).putInt(magic).array());
        }
    }

    private void writeFully(FileChannel chan, ByteBuffer buf) throws IOException {
        buf.flip();
        while (buf.hasRemaining()) {
            chan.write(buf);
        }
    }

    private void checkCondition() {
        if (allowParallel) {
            Validate.isTrue(!compress && !allowGunzip);
        }
    }

    private void writeEntries(
            List<Path> sortedEntries,
            SerialBundlerWriter writer,
            boolean allowGunzip) throws IOException {
        for (Path sortedEntry : sortedEntries) {
            var fileName = sortedEntry.getFileName();
            var shouldGunzip = allowGunzip && (fileName.endsWith("mca") || fileName.endsWith("dat"));
            if (shouldGunzip) {
                writer.writeGunzip(dir.root().resolve(sortedEntry), sortedEntry.toString());
            } else {
                writer.writePlain(dir.root().resolve(sortedEntry), sortedEntry.toString());
            }
        }
    }

}
