package io.ib67.sfcraft.strategy;

import io.ib67.sfcraft.Backup;
import io.ib67.sfcraft.WorldDir;
import io.ib67.sfcraft.bundler.Bundle;
import io.ib67.sfcraft.config.StorageOption;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.UploadObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
public class S3BackupStrategy implements BackupStrategy {
    protected final StorageOption.S3 option;
    protected final MinioClient minio;
    protected final AtomicBoolean uploading = new AtomicBoolean(false);

    @SneakyThrows
    public S3BackupStrategy(StorageOption.S3 option) {
        this.option = option;
        this.minio = MinioClient.builder()
                .endpoint(URI.create(option.endpoint()).toURL())
                .credentials(option.accessKey(), option.secretKey())
                .region(option.region())
                .build();
        if (option.enforcePathStyle()) {
            minio.disableVirtualStyleEndpoint();
        } else {
            minio.enableVirtualStyleEndpoint();
        }
        log.info("Testing S3 credentials");
        var exists = minio.bucketExists(BucketExistsArgs.builder().bucket(option.bucket()).build());
        if (!exists) throw new IllegalStateException("Bucket '" + option.bucket() + "' does not exist");
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
                minio.uploadObject(UploadObjectArgs.builder()
                        .contentType("application/octet-stream")
                        .object(object)
                        .filename(pathToBundle.toString())
                        .build());
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
    public void recoverBackup(Backup backup, Path restorePath) {
        //todo http range
    }

    @Override
    @SneakyThrows
    public void deleteBackup(Backup backup) {
        minio.removeObject(RemoveObjectArgs.builder()
                        .bucket(backup.backupKey()).build());
    }

    @Override
    public boolean isAvailable() {
        if (uploading.get()) {
            log.warn("Last S3 Backup is still uploading!");
            return false;
        }
        return true;
    }
}
