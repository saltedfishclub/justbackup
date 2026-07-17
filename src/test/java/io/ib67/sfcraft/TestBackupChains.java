package io.ib67.sfcraft;

import io.ib67.sfcraft.strategy.BackupChains;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestBackupChains {
    private static Backup full(String key, String subject, long at) {
        return new Backup(key, key, "local", "world", subject, false, at, 1);
    }

    private static Backup incr(String key, String subject, long at) {
        return new Backup(key, key, "local", "world", subject, true, at, 1);
    }

    private static Backup legacy(String key) {
        return new Backup(key, key, "local", "world", null, false, 0, 1);
    }

    @Test
    public void fullBackupRestoresAlone() {
        var full = full("f1", "world", 100);
        var all = List.of(full, incr("i1", "world", 200));
        assertEquals(List.of(full), BackupChains.resolveChain(all, full));
    }

    @Test
    public void incrementalChainsBackToItsBase() {
        var f1 = full("f1", "world", 100);
        var i1a = incr("i1a", "world", 200);
        var i1b = incr("i1b", "world", 300);
        var f2 = full("f2", "world", 400);
        var i2a = incr("i2a", "world", 500);
        var all = List.of(i2a, f1, i1b, f2, i1a); // deliberately unordered

        assertEquals(List.of(f1, i1a, i1b), BackupChains.resolveChain(all, i1b));
        assertEquals(List.of(f2, i2a), BackupChains.resolveChain(all, i2a));
    }

    @Test
    public void chainIgnoresOtherSubjects() {
        var f1 = full("f1", "world", 100);
        var otherFull = full("fo", "nether", 150);
        var i1 = incr("i1", "world", 200);
        assertEquals(List.of(f1, i1), BackupChains.resolveChain(List.of(f1, otherFull, i1), i1));
    }

    @Test
    public void orphanIncrementalIsRejected() {
        var i1 = incr("i1", "world", 200);
        assertThrows(IllegalStateException.class,
                () -> BackupChains.resolveChain(List.of(i1), i1));
    }

    @Test
    public void legacyEntryRestoresStandalone() {
        var old = legacy("old");
        assertEquals(List.of(old), BackupChains.resolveChain(List.of(old), old));
    }

    @Test
    public void rotationKeepsWholeGenerations() {
        var f1 = full("f1", "world", 100);
        var i1 = incr("i1", "world", 200);
        var f2 = full("f2", "world", 300);
        var i2 = incr("i2", "world", 400);
        var f3 = full("f3", "world", 500);
        var all = List.of(f1, i1, f2, i2, f3);

        assertEquals(List.of(), BackupChains.rotationVictims(all, "world", 3));
        assertEquals(List.of(f1, i1), BackupChains.rotationVictims(all, "world", 2));
        assertEquals(List.of(f1, i1, f2, i2), BackupChains.rotationVictims(all, "world", 1));
    }

    @Test
    public void rotationNeverTouchesLegacyOrOrphans() {
        var orphan = incr("orphan", "world", 50);
        var old = legacy("old");
        var f1 = full("f1", "world", 100);
        var f2 = full("f2", "world", 200);
        var all = List.of(orphan, old, f1, f2);

        assertEquals(List.of(f1), BackupChains.rotationVictims(all, "world", 1));
    }
}
