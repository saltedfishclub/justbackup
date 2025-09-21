package io.ib67.sfcraft;

import lombok.SneakyThrows;

import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record WorldDir(
        Path root
) {
    public Path region() {
        return root.resolve("region");
    }

    public Path poi() {
        return root.resolve("poi");
    }

    public Path entities() {
        return root.resolve("entities");
    }

    @SneakyThrows
    public List<WorldDir> otherDimensions() {
        try (var w = Files.walk(root)) {
            return w.filter(it -> Files.exists(it.resolve("region")) && Files.exists(it.resolve("poi")))
                    .map(WorldDir::new)
                    .toList();
        }
    }

    /**
     * @return Files expect poi, region, entities, playerdata and things in dimension folder.
     */
    @SneakyThrows
    public List<Path> otherFiles() {
        var otherDimensions = otherDimensions();
        try (var f = Files.walk(root)) {
            return f.filter(it -> otherDimensions.stream().noneMatch(a -> it.startsWith(a.root)))
                    .toList();
        }
    }

    @SneakyThrows
    public List<Path> everything() {
        try (var f = Files.walk(root)) {
            return f.toList();
        }
    }
}
