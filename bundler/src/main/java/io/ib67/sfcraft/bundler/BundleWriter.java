package io.ib67.sfcraft.bundler;

import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import io.ib67.kiwi.routine.Uni;
import io.netty.buffer.AdaptiveByteBufAllocator;
import io.netty.buffer.ByteBufAllocator;
import lombok.Builder;
import lombok.SneakyThrows;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPInputStream;

public class BundleWriter implements Closeable {
    public static Comparator<Path> SORT = BundleWriter::preferRegion;
    protected static final ByteBufAllocator ALLOC = new AdaptiveByteBufAllocator();
    protected final boolean allowGunzip;
    protected final int compressionLevel;
    protected final Path relativeRoot;
    protected final EntryOutputStream outputStream;
    protected final byte[] buffer;

    @SneakyThrows
    @Builder
    public BundleWriter(
            boolean allowGunzip,
            int compressionLevel,
            int maxWorkers,
            Path relativeRoot,
            OutputStream outputStream,
            byte[] dictionary
    ) {
        this.allowGunzip = allowGunzip;
        this.compressionLevel = compressionLevel;
        this.relativeRoot = relativeRoot;;
        var zstdOut = new ZstdOutputStreamNoFinalizer(outputStream, compressionLevel);
        if (dictionary != null) {
            zstdOut.setDict(new ZstdDictCompress(dictionary, compressionLevel));
        }
        if(maxWorkers > 1) zstdOut.setWorkers(maxWorkers);
        this.outputStream = new EntryOutputStream(zstdOut);
        this.buffer = new byte[4096];
    }

    public static CompletableFuture<List<Path>> createBundleParallelized(
            Path parentOfBundle,
            List<Path> _paths,
            int threshold,
            Executor executor,
            UnaryOperator<BundleWriterBuilder> config
    ) throws IOException {
        var paths = new ArrayList<>(_paths);
        if (Files.notExists(parentOfBundle)) {
            Files.createDirectories(parentOfBundle);
        }
        // Shuffle once to make elements distribute evenly, then split and sort for
        // the final sequence
        Collections.shuffle(paths);
        if (paths.size() >= threshold) {
            var parallelism = Math.min(
                    Runtime.getRuntime().availableProcessors() / 2
                    , paths.size() / threshold
            ); //todo tuning and debug

            var parted = partition(paths, paths.size() / parallelism);
            var futures = new ArrayList<CompletableFuture<Path>>();
            for (int i = 0; i < parted.size(); i++) {
                var pathList = parted.get(i);
                var output = parentOfBundle.resolve("Worker_" + System.currentTimeMillis() + "_" + i + ".jpack");
                var future = CompletableFuture.supplyAsync(() -> createBundleWorker(pathList, output, config), executor);
                futures.add(future);
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply((v) ->
                    Uni.from(futures::forEach).map(CompletableFuture::join).toList());
        } else {
            return CompletableFuture.supplyAsync(() -> {
                var singleResult = parentOfBundle.resolve("result.zst");
                try {
                    createBundle(singleResult, paths, config);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return List.of(singleResult);
            });
        }
    }

    @SneakyThrows
    private static Path createBundleWorker(
            List<Path> task,
            Path output,
            UnaryOperator<BundleWriterBuilder> config
    ) {
        task.sort(SORT);
        createBundle(output, task, config);
        return output;
    }

    private static <E> List<List<E>> partition(List<E> toPartition, int eachSize) {
        var result = new ArrayList<List<E>>();
        var remain = toPartition.size() % eachSize;
        var groups = (toPartition.size() - remain) / eachSize;
        for (int i = 0; i < groups; i++) {
            result.add(toPartition.subList(i * eachSize, (i + 1) * eachSize));
        }
        if (remain != 0) result.add(toPartition.subList(groups * eachSize, groups * eachSize + remain));
        return result;
    }

    public static int preferRegion(Path a, Path b) {
        boolean aMatch = a.getParent().toString().equals("region") && a.endsWith(".mca");
        boolean bMatch = b.getParent().toString().equals("region") && b.endsWith(".mca");

        if (aMatch && !bMatch) return -1;
        if (!aMatch && bMatch) return 1;
        return a.compareTo(b);
    }

    public static void createBundle(
            Path pathToBundle,
            Iterable<Path> path,
            UnaryOperator<BundleWriterBuilder> config
    ) throws IOException {
        try (var writer = config.apply(BundleWriter.builder())
                .outputStream(Files.newOutputStream(pathToBundle))
                .build()) {
            for (var p : path) {
                if (!Files.isRegularFile(p) || Files.size(p) == 0) continue;
                var s = p.toString();
                writer.write(p, (s.contains("region") && s.endsWith(".mca")) || s.endsWith(".dat"));
            }
        }
    }

    public void write(Path path, boolean suggestGunzip) throws IOException {
        var size = Files.size(path);
        if (suggestGunzip) {
            boolean isGzip;
            try (var raf = new RandomAccessFile(path.toFile(), "r")) {
                isGzip = raf.read() == 0x8b && raf.read() == 0x1f;
            }
            if (isGzip) {
                var buffer = ALLOC.buffer((int) size * 2);
                try (var gunzip = new GZIPInputStream(new FileInputStream(path.toFile()))) {
                    while (buffer.writeBytes(gunzip, 4096) != -1) ;
                    outputStream.writeEntryHeader( // bug
                            relativeRoot.relativize(path).toString(),
                            BundleEntry.ATTR_GUNZIP,
                            size
                    );
                    buffer.readBytes(outputStream, buffer.readableBytes());
                } catch (IOException e) {
                    writePlain(path);
                } finally {
                    buffer.release();
                }
            }
        }

        writePlain(path);
    }

    private void writePlain(Path path) throws IOException {
        var rPath = relativeRoot.toAbsolutePath().relativize(path.toAbsolutePath());
        var buffer = this.buffer;
        var outputStream = this.outputStream;
        outputStream.writeEntryHeader(rPath.toString(), (short) 0, Files.size(path));
        var read = 0;
        try (var fs = Files.newInputStream(path)) {
            while ((read = fs.read(buffer)) > 0) {
                outputStream.write(buffer, 0, read);
            }
        }
    }

    @Override
    public void close() throws IOException {
        outputStream.close();
    }
}
