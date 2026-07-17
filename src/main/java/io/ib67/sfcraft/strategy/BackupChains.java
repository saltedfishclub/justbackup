package io.ib67.sfcraft.strategy;

import io.ib67.sfcraft.Backup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure functions over the backup index: resolving restore chains (full base + following
 * incrementals) and grouping backups into generations for rotation. Legacy entries
 * (created before subject/createdAt existed) are excluded from both — they are restorable
 * standalone but never chained or rotated away.
 */
public final class BackupChains {
    private BackupChains() {
    }

    /**
     * Resolves the list of bundles that must be extracted, in order, to restore {@code target}.
     * A full backup restores alone; an incremental needs its most recent full base and every
     * incremental in between.
     *
     * @throws IllegalStateException if the target is an incremental without a reachable base
     */
    public static List<Backup> resolveChain(Collection<Backup> tracked, Backup target) {
        if (!target.incremental()) return List.of(target);
        if (target.legacy()) {
            throw new IllegalStateException("Backup " + target.backupKey()
                    + " lacks chain metadata (created by an older version) and cannot be restored incrementally.");
        }
        var chain = new ArrayList<Backup>();
        tracked.stream()
                .filter(b -> !b.legacy() && Objects.equals(target.subject(), b.subject()))
                .filter(b -> b.createdAt() < target.createdAt())
                .sorted(Comparator.comparingLong(Backup::createdAt))
                .forEach(b -> {
                    if (!b.incremental()) chain.clear(); // a newer full base supersedes older history
                    chain.add(b);
                });
        chain.add(target);
        if (chain.getFirst().incremental()) {
            throw new IllegalStateException("No full backup found before " + target.backupKey()
                    + "; cannot restore an incremental backup without its full base.");
        }
        return List.copyOf(chain);
    }

    /**
     * Groups a subject's backups into generations: each full backup starts a new generation
     * that collects the incrementals after it. Incrementals older than the first full backup
     * (orphans) belong to no generation.
     */
    public static List<List<Backup>> generations(Collection<Backup> tracked, String subject) {
        var result = new ArrayList<List<Backup>>();
        List<Backup> current = null;
        var sorted = tracked.stream()
                .filter(b -> !b.legacy() && Objects.equals(subject, b.subject()))
                .sorted(Comparator.comparingLong(Backup::createdAt))
                .toList();
        for (var b : sorted) {
            if (!b.incremental()) {
                current = new ArrayList<>();
                result.add(current);
            }
            if (current != null) current.add(b);
        }
        return result;
    }

    /**
     * @return the backups that fall out of the newest {@code keepGenerations} generations and
     * should be deleted. Whole generations only — an incremental is never separated from its base.
     */
    public static List<Backup> rotationVictims(Collection<Backup> tracked, String subject, int keepGenerations) {
        var generations = generations(tracked, subject);
        if (generations.size() <= keepGenerations) return List.of();
        return generations.subList(0, generations.size() - keepGenerations).stream()
                .flatMap(List::stream)
                .toList();
    }
}
