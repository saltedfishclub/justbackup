package io.ib67.sfcraft;

import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Log4j2
public class FileWatcherThread extends Thread {
    private static final long VALIDATE_INTERVAL_MILLIS = 10_000;

    public interface Listener {
        void onChange(String subject, Path file);

        /**
         * Events for this subject were lost (OS queue overflow, or its directory tree was
         * replaced underneath us); the accumulated change set can no longer be trusted and
         * the next backup should be a full one.
         */
        void onOverflow(String subject);
    }

    /** subject name -> root directory (absolute) */
    protected final Map<String, Path> subjectRoots = new HashMap<>();
    /** subject name -> fileKey (inode) of the root at registration time */
    protected final Map<String, Object> subjectRootKeys = new HashMap<>();
    /** watched directory (absolute) -> subject name */
    protected final Map<Path, String> watchedDirs = new HashMap<>();
    protected final Map<WatchKey, Path> keyToDir = new HashMap<>();
    protected final Listener listener;
    private WatchService watcher;
    private long lastValidation;

    @SneakyThrows
    public FileWatcherThread(
            Map<String, Path> watchSubject,
            Listener listener
    ) {
        for (var entry : watchSubject.entrySet()) {
            var root = entry.getValue();
            if (Files.notExists(root)) {
                log.error("{} not found. Creating directory for it..", root);
                Files.createDirectories(root);
            }
            subjectRoots.put(entry.getKey(), root.toAbsolutePath());
        }
        this.listener = listener;
        setName("JustBackup/FileWatcher");
        setDaemon(true);
    }

    @Override
    @SneakyThrows
    public void run() {
        try (var watchService = FileSystems.getDefault().newWatchService()) {
            this.watcher = watchService;
            for (var entry : subjectRoots.entrySet()) {
                registerSubject(entry.getKey(), false);
            }
            log.info("Watching {} directories for {} subjects", keyToDir.size(), subjectRoots.size());
            lastValidation = System.currentTimeMillis();
            for (; ; ) {
                var key = watcher.poll(10, TimeUnit.SECONDS);
                if (Thread.interrupted()) return;
                if (System.currentTimeMillis() - lastValidation >= VALIDATE_INTERVAL_MILLIS) {
                    validateRoots();
                }
                if (key == null) continue;
                var dir = keyToDir.get(key);
                var subject = dir == null ? null : watchedDirs.get(dir);
                for (var ev : key.pollEvents()) {
                    if (subject == null) {
                        log.warn("Ignoring changes in unknown watch key {}", dir);
                        continue;
                    }
                    var kind = ev.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        log.warn("Watch events for {} overflowed; forcing a full backup on next run.", subject);
                        listener.onOverflow(subject);
                        continue;
                    }
                    var path = dir.resolve((Path) ev.context());
                    log.debug("event {} on {} ({})", kind, path, subject);
                    if (Files.isRegularFile(path)) {
                        listener.onChange(subject, path);
                    } else if (Files.isDirectory(path) && kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        // report contained files too: they may have been created before we
                        // managed to register and would never produce their own events
                        registerTree(path, subject, true);
                    }
                }
                if (!key.reset()) {
                    log.debug("Watch key for {} is no longer valid, dropping it", dir);
                    keyToDir.remove(key);
                    if (dir != null) watchedDirs.remove(dir);
                }
            }
        } catch (InterruptedException interruptedException) {
            log.info("FileWatcherThread is quitting!");
        }
    }

    /**
     * Watch keys silently go stale when a watched root is replaced rather than modified —
     * e.g. a world format conversion moves the directory aside and recreates it, leaving us
     * watching an orphaned inode. Compare the root's fileKey (inode) against what we
     * registered; on mismatch, re-register the whole subject and force a full backup since
     * events were lost in between.
     */
    private void validateRoots() {
        lastValidation = System.currentTimeMillis();
        for (var entry : subjectRoots.entrySet()) {
            var subject = entry.getKey();
            var root = entry.getValue();
            Object currentKey = fileKeyOf(root);
            if (java.util.Objects.equals(currentKey, subjectRootKeys.get(subject))) continue;
            log.warn("Root of subject {} ({}) was replaced or recreated; re-registering watches and forcing a full backup.",
                    subject, root);
            registerSubject(subject, true);
        }
    }

    private static Object fileKeyOf(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class).fileKey();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * (Re-)registers a subject's whole directory tree. Existing watches of the subject are
     * cancelled first; when {@code lostEvents} is set, the subject is flagged for a full
     * backup because changes may have happened while we were not watching.
     */
    private void registerSubject(String subject, boolean lostEvents) {
        keyToDir.entrySet().removeIf(e -> {
            if (subject.equals(watchedDirs.get(e.getValue()))) {
                e.getKey().cancel();
                return true;
            }
            return false;
        });
        watchedDirs.values().removeIf(subject::equals);

        var root = subjectRoots.get(subject);
        subjectRootKeys.put(subject, fileKeyOf(root));
        if (Files.isDirectory(root)) {
            // no need to report existing files: the startup full backup (initial call) or the
            // forced full backup (lostEvents) already covers them
            registerTree(root, subject, false);
        } else {
            log.error("Root of subject {} ({}) does not exist, nothing is watched for it.", subject, root);
        }
        if (lostEvents) listener.onOverflow(subject);
    }

    private void registerTree(Path root, String subject, boolean reportFiles) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (var p : walk.toList()) {
                if (Files.isDirectory(p)) {
                    var abs = p.toAbsolutePath();
                    var key = abs.register(watcher,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY);
                    watchedDirs.put(abs, subject);
                    keyToDir.put(key, abs);
                } else if (reportFiles && Files.isRegularFile(p)) {
                    listener.onChange(subject, p);
                }
            }
        } catch (IOException e) {
            log.warn("Cannot watch directory tree {}; forcing a full backup on next run.", root, e);
            listener.onOverflow(subject);
        }
    }
}
