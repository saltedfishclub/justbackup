package io.ib67.sfcraft;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * A directory tree being backed up, plus the changes the watcher saw since the last
 * incremental backup: files that were created/modified, and files that were deleted. All
 * access goes through synchronized methods so the watcher's events and the worker's drain can
 * never interleave badly (no lost events, no ConcurrentModificationException while bundling).
 */
public final class BackupSubject {
    /** A drained snapshot of changes: touched files to bundle, deleted files to tombstone. */
    public record Changes(Set<Path> changed, Set<Path> deleted) {
    }

    private final String name;
    private final Path root;
    private final Set<Path> changedFiles = new HashSet<>();
    private final Set<Path> deletedFiles = new HashSet<>();
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

    /** A create/modify supersedes any pending deletion of the same path. */
    public synchronized void addChanged(Path file) {
        deletedFiles.remove(file);
        changedFiles.add(file);
    }

    /** A deletion supersedes any pending create/modify of the same path. */
    public synchronized void addDeleted(Path file) {
        changedFiles.remove(file);
        deletedFiles.add(file);
    }

    /**
     * Atomically takes ownership of the accumulated changes, leaving empty sets behind.
     * If the backup fails, give them back via {@link #mergeBack(Changes)}.
     */
    public synchronized Changes drain() {
        var changed = new HashSet<>(changedFiles);
        var deleted = new HashSet<>(deletedFiles);
        changedFiles.clear();
        deletedFiles.clear();
        return new Changes(changed, deleted);
    }

    /**
     * Restores drained changes after a failed backup, without clobbering newer events: a path
     * the watcher has since seen change is not re-marked deleted, and vice versa.
     */
    public synchronized void mergeBack(Changes changes) {
        for (var f : changes.changed()) {
            if (!deletedFiles.contains(f)) changedFiles.add(f);
        }
        for (var f : changes.deleted()) {
            if (!changedFiles.contains(f)) deletedFiles.add(f);
        }
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
        if (v) {
            // a full backup snapshots the live filesystem, so pending deltas are moot
            changedFiles.clear();
            deletedFiles.clear();
        }
        return v;
    }
}
