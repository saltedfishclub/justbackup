package io.ib67.sfcraft.strategy;

import io.ib67.sfcraft.Backup;
import io.ib67.sfcraft.bundler.Bundle;
import io.ib67.sfcraft.config.StorageOption;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
public class S3BackupStrategy implements BackupStrategy {
    protected final AtomicBoolean uploading = new AtomicBoolean(false);
    protected final StorageOption.S3 option;
    protected final S3Client s3;
    protected final Path temporaryDownloadPath;

    @SneakyThrows
    public S3BackupStrategy(StorageOption.S3 option, Path tempDownloadPath) {
        this.option = option;
        var credential = AwsBasicCredentials.create(option.accessKey(), option.secretKey());
        this.s3 = S3Client.builder()
                .region(Region.of(option.region()))
                .forcePathStyle(option.enforcePathStyle())
                .endpointOverride(URI.create(option.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(credential))
                .build();
        log.info("Testing S3 credentials");
        // throws exception if the bucket neither inaccessible nor not exist
        s3.headBucket(b -> b.bucket(option.bucket()));
        this.temporaryDownloadPath = Objects.requireNonNull(tempDownloadPath);
        if (Files.notExists(temporaryDownloadPath))
            Files.createDirectories(temporaryDownloadPath);
    }

    @Override
    @SneakyThrows
    public Backup createBackup(Path from, Path pathToBundle) {
        if (!uploading.compareAndSet(false, true)) {
            throw new IllegalStateException("Last upload is not done yet! keep waiting...");
        }
        int i = 0;
        do {
            try {
                var object = option.prefix() + "/" + pathToBundle.getFileName().toString();
                s3.putObject(o -> o.bucket(option.bucket())
                        .contentType("application/octet-stream")
                        .ifNoneMatch("*") // to avoid content overriding
                        .key(object).build(), pathToBundle);
                //todo also calculate sha?
                return new Backup(pathToBundle.getFileName().toString(),
                        object, "s3",
                        from.toString(),
                        false,
                        Files.size(pathToBundle));
            } catch (IOException e) {
                if (i >= option.retryAmount()) {
                    throw new IllegalStateException("Cannot upload " + pathToBundle + " to S3");
                }
                var nextTry = Math.pow(2, i++);
                log.error("Error uploading S3. trying in {}s later. ({}/{})", nextTry, i, option.retryAmount(), e);
                Thread.sleep((long) (nextTry * 1000));
            } catch (Exception e) {
                throw new IllegalStateException("Unrecoverable exception occurred when uploading " + pathToBundle, e);
            } finally {
                uploading.set(false);
            }
        } while (true);
    }

    @Override
    @SneakyThrows
    public void recoverBackup(Backup backup, Path restorePath) {
        var resp = s3.getObject(b -> b.bucket(option.bucket()).key(backup.backupKey()));
        var target = temporaryDownloadPath.resolve("s3_" + System.currentTimeMillis());
        try (var fs = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            resp.transferTo(fs);
            Bundle.unbundleFile(target, restorePath);
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Override
    @SneakyThrows
    public void deleteBackup(Backup backup) {
        s3.deleteObject(b -> b.bucket(option.bucket()).key(backup.backupKey()).build());
    }

    @Override
    public boolean isAvailable() {
        if (uploading.get()) {
            log.warn("Last S3 Backup is still uploading!");
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        s3.close();
    }
}
