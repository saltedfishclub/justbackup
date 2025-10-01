package io.ib67.sfcraft;

import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Log4j2
public class FileWatcherThread extends Thread {
    protected final Map<Path, String> watchSubject;
    protected final BiConsumer<String, Path> eventListener;

    public FileWatcherThread(
            Map<String, Path> watchSubject,
            BiConsumer<String, Path> eventListener
    ) {
        this.watchSubject = watchSubject.entrySet()
                .stream()
                // todo support for nonexist dir
                .flatMap(it -> walkThrough(it.getValue()).map(r -> Map.entry(r.toAbsolutePath(), it.getKey())))
                .filter(it->Files.isDirectory(it.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
        this.eventListener = eventListener;
        setName("JustBackup/FileWatcher");
        setDaemon(true);
    }

    @SneakyThrows
    private static Stream<Path> walkThrough(Path path) {
        if(Files.notExists(path)){
            log.error("{} not found. Creating directory for it..", path);
            Files.createDirectories(path);
        }
        return Files.walk(path);
    }

    @Override
    @SneakyThrows
    public void run() {
        try (var watcher = FileSystems.getDefault().newWatchService()) {
            var keyToParent = new HashMap<WatchKey, Path>();
            for (Path value : watchSubject.keySet()) {
                var key = value.register(
                        watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY
                );
                keyToParent.put(key, value);
            }
            for (; ; ) {
                var key = watcher.poll(10, TimeUnit.SECONDS);
                if (key == null) continue;
                if (Thread.interrupted()) return;
                for (var ev : key.pollEvents()) {
                    var kind = ev.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW)
                        continue;
                    var parent = keyToParent.get(key);
                    var subject = watchSubject.get(parent);
                    if(subject == null) {
                        log.warn("Ignoring changes in "+parent);
                        continue;
                    }
                    var path = parent.resolve(((WatchEvent<Path>) ev).context());
                    if (Files.isRegularFile(path)) {
                        eventListener.accept(subject, parent);
                    } else if (Files.isDirectory(path) && kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        var nK = path.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                        watchSubject.put(path, subject);
                        keyToParent.put(nK, path);
                    }
                }
                key.reset();
            }
        }catch (InterruptedException interruptedException){
            log.info("FileWatcherThread is quitting!");
        }
    }
}
