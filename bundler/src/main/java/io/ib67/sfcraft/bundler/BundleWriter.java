package io.ib67.sfcraft.bundler;

import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import io.ib67.kiwi.routine.Uni;
import io.ib67.sfcraft.bundler.region.RegionFile;
import io.ib67.sfcraft.bundler.region.RegionParser;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;

import lombok.Builder;
import lombok.SneakyThrows;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;

public class BundleWriter implements Closeable {
    public static Comparator<Path> SORT = BundleWriter::preferRegion;
    protected static final ThreadLocal<byte[]> buffer = ThreadLocal.withInitial(() -> new byte[4096]);
    protected final ByteBufAllocator allocator;
    protected final boolean allowReassemble;
    protected final int compressionLevel;
    protected final boolean verbose;
    protected final Path relativeRoot;
    protected final EntryOutputStream outputStream;

    @SneakyThrows
    @Builder
    public BundleWriter(
            boolean allowGunzip,
            int compressionLevel,
            int maxWorkers,
            Path relativeRoot,
            OutputStream outputStream,
            byte[] dictionary, ByteBufAllocator allocator, boolean verbose
    ) {
        this.allowReassemble = allowGunzip;
        this.compressionLevel = compressionLevel;
        this.relativeRoot = relativeRoot;
        this.allocator = allocator == null ? ByteBufAllocator.DEFAULT : allocator;
        this.verbose = verbose;
        var zstdOut = new ZstdOutputStreamNoFinalizer(outputStream, compressionLevel);
        if (dictionary != null) {
            zstdOut.setDict(new ZstdDictCompress(dictionary, compressionLevel));
        }
        if (maxWorkers > 1) zstdOut.setWorkers(maxWorkers);
        this.outputStream = new EntryOutputStream(zstdOut);
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
                var output = parentOfBundle.resolve("Worker_" + System.currentTimeMillis() + "_" + i + ".swb.zst");
                var future = CompletableFuture.supplyAsync(() -> createBundleWorker(pathList, output, config), executor);
                futures.add(future);
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply((v) ->
                    futures.stream().map(CompletableFuture::join).toList());
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
        if (suggestGunzip && allowReassemble) {
            //todo size limit
            writeReassembleInMem(path);
            return;
        }
        writePlain(path);
    }

    @SneakyThrows
    private void writeReassembleInMem(Path path) {
        if (!"region".equals(path.getParent().toString()) && !path.toString().endsWith(".mca")) { // todo use pattern matcher
            if(verbose) System.out.println("Mismatch " + path + ", parent: " + path.getParent());
            writePlain(path);
            return;
        }
        if(verbose) System.out.println("Reassembling " + path);
        var finalResult = allocator.buffer();
        var rPath = relativeRoot.toAbsolutePath().relativize(path.toAbsolutePath());
        try (var out = new EntryOutputStream(new ByteBufOutputStream(finalResult));
             var rf = new RegionParser(path, allocator)) {
            // uncompressed result
            var result = rf.writeReassembled(RegionFile.CompressType.NONE);
            var length = result.readableBytes();
            out.writeEntryHeader(rPath.toString(), BundleEntry.ATTR_REASSEMBLE, length, System.currentTimeMillis());
            result.readBytes(out, length);
            result.release();
            // all done
        } catch (IOException e) {
            System.err.println("Failed to parse region " + path + ": " + e);
            finalResult.release();
            writePlain(path);
            return;
        }
        finalResult.readBytes(outputStream, finalResult.readableBytes());
        finalResult.release();

    }

    private void writePlain(Path path) throws IOException {
        var rPath = relativeRoot.toAbsolutePath().relativize(path.toAbsolutePath());
        var outputStream = this.outputStream;
        outputStream.writeEntryHeader(rPath.toString(), (short) 0, Files.size(path), 0);
        var read = 0;
        try (var fs = Files.newInputStream(path)) {
            var buffer = BundleWriter.buffer.get();
            while ((read = fs.read(buffer)) > 0) {
                outputStream.write(buffer, 0, read);
            }
        }catch(IOException e){
            System.err.println("Error while bundling "+path);
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        outputStream.close();
    }
}
