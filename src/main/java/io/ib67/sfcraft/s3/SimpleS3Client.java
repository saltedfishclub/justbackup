package io.ib67.sfcraft.s3;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A tiny synchronous S3 client on top of {@link HttpClient}, covering only what
 * this mod needs: headBucket, putObject, getObject and deleteObject. Requests
 * are authenticated with AWS Signature Version 4 and work against any
 * S3-compatible endpoint, in both path-style and virtual-hosted style.
 */
public final class SimpleS3Client implements AutoCloseable {
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final HttpClient http;
    private final String scheme;
    // authority as HttpClient will put it into the Host header: default ports stripped
    private final String hostPort;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final boolean pathStyle;

    public SimpleS3Client(String endpoint, String region, String accessKey, String secretKey, boolean pathStyle) {
        var uri = URI.create(endpoint);
        this.scheme = Objects.requireNonNull(uri.getScheme(), "endpoint has no scheme: " + endpoint);
        Objects.requireNonNull(uri.getHost(), "endpoint has no host: " + endpoint);
        var defaultPort = "https".equals(scheme) ? 443 : 80;
        this.hostPort = uri.getPort() == -1 || uri.getPort() == defaultPort
                ? uri.getHost()
                : uri.getHost() + ":" + uri.getPort();
        this.region = Objects.requireNonNull(region);
        this.accessKey = Objects.requireNonNull(accessKey);
        this.secretKey = Objects.requireNonNull(secretKey);
        this.pathStyle = pathStyle;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Throws {@link S3Exception} if the bucket does not exist or the credentials
     * cannot access it.
     */
    public void headBucket(String bucket) throws IOException, InterruptedException {
        var target = target(bucket, null);
        var request = signed("HEAD", target, AwsV4Signer.EMPTY_PAYLOAD_SHA256)
                .HEAD().build();
        var response = http.send(request, HttpResponse.BodyHandlers.discarding());
        ensureSuccess("HEAD " + target.uri(), response.statusCode(), null);
    }

    public void putObject(String bucket, String key, Path file, String contentType)
            throws IOException, InterruptedException {
        var target = target(bucket, key);
        var request = signed("PUT", target, sha256Hex(file))
                .header("content-type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofFile(file))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess("PUT " + target.uri(), response.statusCode(), response.body());
    }

    /**
     * Returns the object body; the caller is responsible for closing it.
     */
    public InputStream getObject(String bucket, String key) throws IOException, InterruptedException {
        var target = target(bucket, key);
        var request = signed("GET", target, AwsV4Signer.EMPTY_PAYLOAD_SHA256)
                .GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            String body;
            try (var in = response.body()) {
                body = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
            }
            throw new S3Exception("GET " + target.uri(), response.statusCode(), body);
        }
        return response.body();
    }

    public void deleteObject(String bucket, String key) throws IOException, InterruptedException {
        var target = target(bucket, key);
        var request = signed("DELETE", target, AwsV4Signer.EMPTY_PAYLOAD_SHA256)
                .DELETE().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess("DELETE " + target.uri(), response.statusCode(), response.body());
    }

    @Override
    public void close() {
        http.close();
    }

    private record Target(URI uri, String host, String encodedPath) {
    }

    private Target target(String bucket, String key) {
        var host = pathStyle ? hostPort : bucket + "." + hostPort;
        var path = new StringBuilder();
        if (pathStyle) {
            path.append('/').append(URLEncoder.encode(bucket, Charset.defaultCharset()));
        }
        if (key != null) {
            for (var segment : key.split("/", -1)) {
                path.append('/').append(URLEncoder.encode(segment, Charset.defaultCharset()));
            }
        }
        if (path.isEmpty()) {
            path.append('/');
        }
        return new Target(URI.create(scheme + "://" + host + path), host, path.toString());
    }

    /**
     * Host is signed but never set explicitly — HttpClient forbids that and
     * derives it from the URI; {@link #hostPort} mirrors that derivation.
     */
    private HttpRequest.Builder signed(String method, Target target, String payloadHash) {
        var amzDate = AMZ_DATE.format(Instant.now());
        var headers = new TreeMap<String, String>();
        headers.put("host", target.host());
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        var authorization = AwsV4Signer.authorization(method, target.encodedPath(), "",
                headers, payloadHash, amzDate, region, "s3", accessKey, secretKey);
        return HttpRequest.newBuilder(target.uri())
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("authorization", authorization);
    }

    private static void ensureSuccess(String request, int statusCode, String body) throws S3Exception {
        if (statusCode / 100 != 2) {
            throw new S3Exception(request, statusCode, body);
        }
    }

    private static String sha256Hex(Path file) throws IOException {
        var digest = AwsV4Signer.sha256();
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
