package io.ib67.sfcraft;

import java.nio.file.Path;
import java.util.List;

public record BackupSubject(
        String name,
        Path root,
        List<Path> changedFiles
) {
}
