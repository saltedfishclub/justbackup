package io.ib67.sfcraft.config;

import org.apache.commons.lang3.Validate;

public sealed interface StorageOption {
    String type();

    record S3(
            String region,
            String accessKey,
            String secretKey,
            String endpoint,
            String bucket,
            String prefix,
            boolean enforcePathStyle,
            int retryAmount
    ) implements StorageOption {
        @Override
        public String type() {
            return "s3";
        }

        public S3 {
            Validate.isTrue(retryAmount >= 0);
        }
    }

    record Local(
            String saveDir,
            long diskSizeQuotaBytes
    ) implements StorageOption {
        @Override
        public String type() {
            return "local";
        }

        public Local {
            Validate.isTrue(diskSizeQuotaBytes >= 0);
        }
    }
}
