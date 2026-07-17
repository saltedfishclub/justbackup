package io.ib67.sfcraft.s3;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.SortedMap;
import java.util.StringJoiner;

/**
 * Minimal AWS Signature Version 4 signer for S3.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html">
 * Authenticating Requests: Using the Authorization Header (AWS Signature Version 4)</a>
 */
final class AwsV4Signer {
    // SHA-256 of a zero-length payload
    static final String EMPTY_PAYLOAD_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final HexFormat HEX = HexFormat.of();

    private AwsV4Signer() {
    }

    /**
     * @param encodedPath already URI-encoded absolute path, exactly as sent on the wire
     * @param headers     headers to sign; keys must be lowercase, values trimmed.
     *                    Must include host, x-amz-date and x-amz-content-sha256.
     */
    static String authorization(String method, String encodedPath, String canonicalQuery,
                                SortedMap<String, String> headers, String payloadHash,
                                String amzDate, String region, String service,
                                String accessKey, String secretKey) {
        var canonicalHeaders = new StringBuilder();
        var signedHeaders = new StringJoiner(";");
        for (var header : headers.entrySet()) {
            canonicalHeaders.append(header.getKey()).append(':').append(header.getValue()).append('\n');
            signedHeaders.add(header.getKey());
        }
        var canonicalRequest = method + '\n'
                + encodedPath + '\n'
                + canonicalQuery + '\n'
                + canonicalHeaders + '\n'
                + signedHeaders + '\n'
                + payloadHash;
        var dateStamp = amzDate.substring(0, 8);
        var scope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        var stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        var signingKey = hmac(hmac(hmac(hmac(
                ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp), region), service), "aws4_request");
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ",SignedHeaders=" + signedHeaders
                + ",Signature=" + HEX.formatHex(hmac(signingKey, stringToSign));
    }

    static String sha256Hex(byte[] data) {
        return HEX.formatHex(sha256().digest(data));
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM lacks SHA-256", e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM lacks HmacSHA256", e);
        }
    }
}
