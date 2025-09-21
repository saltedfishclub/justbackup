package io.ib67.sfcraft.config;

public sealed interface StorageOption {
    String type();

    record S3(
            String region,
            String accessKey,
            String secretKey,
            String endpoint,
            String bucket
    ) implements StorageOption {
        @Override
        public String type() {
            return "s3";
        }
    }

    record Local(
            String saveDir
    ) implements StorageOption {
        @Override
        public String type() {
            return "local";
        }
    }
}
