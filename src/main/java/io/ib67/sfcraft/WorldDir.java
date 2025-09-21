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

    public Path playerdata() {
        return root.resolve("playerdata");
    }

    @SneakyThrows
    public Map<String, WorldDir> otherDimensions() {
        var begin = root.resolve("dimensions").toAbsolutePath();
        try (var w = Files.walk(begin, 3)) {
            var _r = w.filter(Files::isDirectory)
                    .filter(it -> Files.exists(it.resolve("region")) && Files.exists(it.resolve("poi")))
                    .collect(Collectors.groupingBy(it -> it.relativize(root).toString(),
                            Collectors.mapping(WorldDir::new, Collectors.toList())));
            var r = new HashMap<String, WorldDir>();
            _r.forEach((k, v) -> {
                assert v.size() <= 1;
                r.put(k, v.getFirst());
            });
            return r;
        }
    }

    /**
     * @return Files expect poi, region, entities, playerdata and things in dimension folder.
     */
    @SneakyThrows
    public List<Path> otherFiles() {
        var exclude = List.of(playerdata(), entities(), region(), poi(), root.resolve("dimensions"));
        try (var f = Files.walk(root)) {
            return f.filter(it -> exclude.stream().noneMatch(it::startsWith)).toList();
        }
    }

    @SneakyThrows
    public List<Path> everything() {
        try (var f = Files.walk(root)) {
            return f.toList();
        }
    }
}
