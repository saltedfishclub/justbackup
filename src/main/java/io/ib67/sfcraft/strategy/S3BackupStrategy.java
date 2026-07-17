package io.ib67.sfcraft.strategy;

import io.ib67.sfcraft.Backup;
import io.ib67.sfcraft.bundler.BundleReader;
import io.ib67.sfcraft.config.StorageOption;
import io.ib67.sfcraft.s3.SimpleS3Client;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

@Log4j2
public class S3BackupStrategy implements BackupStrategy {
    protected final StorageOption.S3 option;
    protected final SimpleS3Client s3;
    protected final Path temporaryDownloadPath;

    @SneakyThrows
    public S3BackupStrategy(StorageOption.S3 option, Path tempDownloadPath) {
        this.option = option;
        this.s3 = new SimpleS3Client(option.endpoint(), option.region(),
                option.accessKey(), option.secretKey(), option.enforcePathStyle());
        log.info("Testing S3 credentials");
        // throws exception if the bucket neither inaccessible nor not exist
        s3.headBucket(option.bucket());
        this.temporaryDownloadPath = Objects.requireNonNull(tempDownloadPath);
        if (Files.notExists(temporaryDownloadPath))
            Files.createDirectories(temporaryDownloadPath);
    }

    @Override
    @SneakyThrows
    public Backup createBackup(String subject, Path source, Path bundle, boolean incremental, long createdAt) {
        // bundle names embed the creation timestamp, so keys are unique and retries idempotent
        var object = option.prefix() + "/" + bundle.getFileName();
        int attempt = 0;
        while (true) {
            try {
                s3.putObject(option.bucket(), object, bundle, "application/octet-stream");
                return new Backup(bundle.getFileName().toString(),
                        object, "s3",
                        source.toString(),
                        subject, incremental, createdAt,
                        Files.size(bundle));
            } catch (IOException e) {
                if (attempt >= option.retryAmount()) {
                    throw new IllegalStateException("Cannot upload " + bundle + " to S3 after "
                            + attempt + " retries", e);
                }
                var backoffSeconds = (long) Math.pow(2, attempt++);
                log.error("Error uploading to S3, retrying in {}s ({}/{})",
                        backoffSeconds, attempt, option.retryAmount(), e);
                Thread.sleep(backoffSeconds * 1000);
            }
        }
    }

    @Override
    @SneakyThrows
    public void recoverBackup(Backup backup, Path restorePath, boolean ignoreDeletions) {
        var target = temporaryDownloadPath.resolve("s3_" + System.currentTimeMillis());
        try {
            try (var resp = s3.getObject(option.bucket(), backup.backupKey());
                 var fs = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                resp.transferTo(fs);
            }
            BundleReader.builder().ignoreDeletions(ignoreDeletions).build().extract(target, restorePath);
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Override
    @SneakyThrows
    public void deleteBackup(Backup backup) {
        s3.deleteObject(option.bucket(), backup.backupKey());
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void close() {
        s3.close();
    }
}
