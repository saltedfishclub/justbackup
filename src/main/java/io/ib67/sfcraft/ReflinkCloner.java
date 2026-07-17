package io.ib67.sfcraft;

import lombok.extern.log4j.Log4j2;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clones files via the Linux {@code FICLONE} ioctl (reflink). On CoW filesystems
 * (btrfs, XFS with reflink, OpenZFS >= 2.2) this is a metadata-only operation: the clone
 * shares extents with the source, so staging a whole backup takes milliseconds and no
 * extra disk space, letting the backup release the world IO lock before compressing.
 * <p>
 * Unsupported setups (non-Linux, ext4, overlayfs, staging dir on a different mount) are
 * detected on first use and cached per subject; callers then fall back to packing while
 * holding the world IO lock.
 */
@Log4j2
public final class ReflinkCloner {
    private static final long FICLONE = 0x40049409L;
    private static final int O_RDONLY = 0;
    private static final int O_WRONLY = 1;
    private static final int O_CREAT = 0x40;
    private static final int O_TRUNC = 0x200;
    private static final int ENOENT = 2;
    private static final int EXDEV = 18;

    private static final MethodHandle OPEN;
    private static final MethodHandle IOCTL;
    private static final MethodHandle CLOSE;
    private static final StructLayout CAPTURE_LAYOUT;
    private static final VarHandle ERRNO;
    private static final boolean AVAILABLE;
    private static final Set<String> UNSUPPORTED_SUBJECTS = ConcurrentHashMap.newKeySet();

    static {
        MethodHandle open = null, ioctl = null, close = null;
        StructLayout capture = null;
        VarHandle errno = null;
        boolean ok = false;
        try {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
                var linker = Linker.nativeLinker();
                var lookup = linker.defaultLookup();
                var captureErrno = Linker.Option.captureCallState("errno");
                capture = Linker.Option.captureStateLayout();
                errno = capture.varHandle(MemoryLayout.PathElement.groupElement("errno"));
                open = linker.downcallHandle(lookup.findOrThrow("open"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                        captureErrno);
                ioctl = linker.downcallHandle(lookup.findOrThrow("ioctl"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT),
                        captureErrno, Linker.Option.firstVariadicArg(2));
                close = linker.downcallHandle(lookup.findOrThrow("close"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                ok = true;
            } else {
                log.info("Reflink fast path is Linux-only; backups will pack while holding the world IO lock.");
            }
        } catch (Throwable t) {
            log.info("Reflink fast path unavailable ({}); backups will pack while holding the world IO lock.", t.toString());
        }
        OPEN = open;
        IOCTL = ioctl;
        CLOSE = close;
        CAPTURE_LAYOUT = capture;
        ERRNO = errno;
        AVAILABLE = ok;
    }

    private ReflinkCloner() {
    }

    /**
     * Clones {@code files} (which must live under {@code subjectRoot}) into
     * {@code stagingRoot}, preserving their relative structure. Files that vanished since
     * being listed are skipped.
     *
     * @return the staging root on success, or null if reflink is unsupported here — in that
     * case any partial staging has been cleaned up and the subject is remembered as
     * unsupported so future backups skip the attempt.
     */
    public static Path tryStage(String subjectName, Path subjectRoot, List<Path> files, Path stagingRoot) {
        if (!AVAILABLE || UNSUPPORTED_SUBJECTS.contains(subjectName)) return null;
        var absRoot = subjectRoot.toAbsolutePath().normalize();
        try {
            Files.createDirectories(stagingRoot);
            for (Path file : files) {
                var src = file.toAbsolutePath().normalize();
                if (!Files.isRegularFile(src)) continue;
                var dst = stagingRoot.resolve(absRoot.relativize(src));
                Files.createDirectories(dst.getParent());
                int err = clone(src, dst);
                if (err == ENOENT) continue; // deleted between listing and cloning
                if (err != 0) {
                    markUnsupported(subjectName, src, err);
                    FsUtil.deleteRecursively(stagingRoot);
                    return null;
                }
            }
            return stagingRoot;
        } catch (Throwable t) {
            log.warn("Reflink staging failed; falling back to packing under the world IO lock.", t);
            UNSUPPORTED_SUBJECTS.add(subjectName);
            try {
                FsUtil.deleteRecursively(stagingRoot);
            } catch (Exception cleanup) {
                log.warn("Cannot clean up staging dir {}", stagingRoot, cleanup);
            }
            return null;
        }
    }

    private static void markUnsupported(String subjectName, Path src, int err) {
        UNSUPPORTED_SUBJECTS.add(subjectName);
        if (err == EXDEV) {
            log.warn("Reflink not possible for subject {}: {} and the temporary backup dir are on " +
                    "different filesystems. Put temporaryBackupDir on the same filesystem as the " +
                    "backup subject to enable the CoW fast path. Falling back to packing under the world IO lock.", subjectName, src);
        } else {
            log.info("Reflink unsupported for subject {} (errno {} on {}); " +
                    "falling back to packing under the world IO lock.", subjectName, err, src);
        }
    }

    private static int clone(Path src, Path dst) throws Throwable {
        try (var arena = Arena.ofConfined()) {
            var capture = arena.allocate(CAPTURE_LAYOUT);
            int srcFd = (int) OPEN.invokeExact(capture, arena.allocateFrom(src.toString()), O_RDONLY, 0);
            if (srcFd < 0) return errno(capture);
            try {
                int dstFd = (int) OPEN.invokeExact(capture, arena.allocateFrom(dst.toString()),
                        O_WRONLY | O_CREAT | O_TRUNC, 0644);
                if (dstFd < 0) return errno(capture);
                try {
                    int result = (int) IOCTL.invokeExact(capture, dstFd, FICLONE, srcFd);
                    return result == 0 ? 0 : errno(capture);
                } finally {
                    var ignored = (int) CLOSE.invokeExact(dstFd);
                }
            } finally {
                var ignored = (int) CLOSE.invokeExact(srcFd);
            }
        }
    }

    private static int errno(MemorySegment capture) {
        return (int) ERRNO.get(capture, 0L);
    }
}
