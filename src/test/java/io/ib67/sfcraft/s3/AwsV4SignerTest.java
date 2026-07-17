package io.ib67.sfcraft.s3;

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test vectors from
 * https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
 */
class AwsV4SignerTest {
    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    @Test
    void getObjectExample() {
        var headers = new TreeMap<String, String>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", AwsV4Signer.EMPTY_PAYLOAD_SHA256);
        headers.put("x-amz-date", "20130524T000000Z");

        var authorization = AwsV4Signer.authorization("GET", "/test.txt", "",
                headers, AwsV4Signer.EMPTY_PAYLOAD_SHA256,
                "20130524T000000Z", "us-east-1", "s3", ACCESS_KEY, SECRET_KEY);

        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                        + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                        + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
                authorization);
    }

    @Test
    void putObjectExample() {
        var payloadHash = AwsV4Signer.sha256Hex("Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8));
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", payloadHash);

        var headers = new TreeMap<String, String>();
        headers.put("date", "Fri, 24 May 2013 00:00:00 GMT");
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", "20130524T000000Z");
        headers.put("x-amz-storage-class", "REDUCED_REDUNDANCY");

        var authorization = AwsV4Signer.authorization("PUT", "/test%24file.text", "",
                headers, payloadHash,
                "20130524T000000Z", "us-east-1", "s3", ACCESS_KEY, SECRET_KEY);

        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                        + "SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class,"
                        + "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
                authorization);
    }
}
