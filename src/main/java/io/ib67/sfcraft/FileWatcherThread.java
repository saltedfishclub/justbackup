package io.ib67.sfcraft;

import lombok.SneakyThrows;

import java.nio.file.*;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileWatcherThread extends Thread {
    protected final Map<Path, String> watchSubject;
    protected final BiConsumer<String, Path> eventListener;

    public FileWatcherThread(
            Map<String, Path> watchSubject,
            BiConsumer<String, Path> eventListener
    ) {
        this.watchSubject = watchSubject.entrySet()
                .stream()
                .flatMap(it -> walkThrough(it.getValue()).map(r -> Map.entry(r, it.getKey())))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
        this.eventListener = eventListener;
        setName("JustBackup/FileWatcher");
    }

    @SneakyThrows
    private static Stream<Path> walkThrough(Path path) {
        return Files.walk(path);
    }

    @Override
    @SneakyThrows
    public void run() {
        try (var watcher = FileSystems.getDefault().newWatchService()) {
            for (Path value : watchSubject.keySet()) {
                value.register(
                        watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY
                );
            }
            for (; ; ) {
                var key = watcher.poll(10, TimeUnit.SECONDS);
                if (key == null) continue;
                if (Thread.interrupted()) return;
                for (var ev : key.pollEvents()) {
                    var kind = ev.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW)
                        continue;
                    var path = ((WatchEvent<Path>) ev).context();
                    var subject = Objects.requireNonNull(watchSubject.get(path.getParent()));
                    if (Files.isRegularFile(path)) {
                        eventListener.accept(subject, path.getParent());
                    } else if (Files.isDirectory(path) && kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        path.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                        watchSubject.put(path, subject);
                    }
                }
                key.reset();
            }
        }
    }
}
