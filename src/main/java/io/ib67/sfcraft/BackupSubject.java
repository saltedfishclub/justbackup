package io.ib67.sfcraft;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * A directory tree being backed up, plus the set of files the watcher saw change since the
 * last incremental backup. All access to the change set goes through synchronized methods so
 * the watcher's adds and the worker's drain can never interleave badly (no lost events, no
 * ConcurrentModificationException while bundling).
 */
public final class BackupSubject {
    private final String name;
    private final Path root;
    private final Set<Path> changedFiles = new HashSet<>();
    private boolean forceFullNext;

    public BackupSubject(String name, Path root) {
        this.name = name;
        this.root = root;
    }

    public String name() {
        return name;
    }

    public Path root() {
        return root;
    }

    public synchronized void addChanged(Path file) {
        changedFiles.add(file);
    }

    /**
     * Atomically takes ownership of the accumulated change set, leaving an empty one behind.
     * If the backup fails, give the files back via {@link #mergeBack(Set)}.
     */
    public synchronized Set<Path> drainChanged() {
        var drained = new HashSet<>(changedFiles);
        changedFiles.clear();
        return drained;
    }

    public synchronized void mergeBack(Set<Path> files) {
        changedFiles.addAll(files);
    }

    /**
     * Marks the next backup as full, used when the watcher reports an OVERFLOW (events were
     * dropped by the OS, so the change set can no longer be trusted).
     */
    public synchronized void forceFullNext() {
        forceFullNext = true;
    }

    public synchronized boolean consumeForceFull() {
        var v = forceFullNext;
        forceFullNext = false;
        if (v) changedFiles.clear();
        return v;
    }
}
