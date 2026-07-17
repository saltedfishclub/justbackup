package io.ib67.sfcraft.s3;

import java.io.IOException;

/**
 * A non-2xx response from the S3 endpoint. Network failures surface as plain
 * {@link IOException}s instead.
 */
public final class S3Exception extends IOException {
    private final int statusCode;

    public S3Exception(String request, int statusCode, String errorBody) {
        super(request + " -> HTTP " + statusCode + (errorBody == null || errorBody.isBlank()
                ? "" : ": " + errorBody.strip()));
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
