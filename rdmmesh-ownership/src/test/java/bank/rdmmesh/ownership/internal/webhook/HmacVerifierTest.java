package bank.rdmmesh.ownership.internal.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import bank.rdmmesh.api.port.SigningKeyPort;

class HmacVerifierTest {

    private static final byte[] KEY =
            "rdmmesh-test-hmac-key-must-be-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);

    private final HmacVerifier verifier = new HmacVerifier(() -> KEY.clone());

    @Test
    void roundtrip_sign_then_verify() {
        byte[] body = "{\"event_id\":\"evt-1\"}".getBytes(StandardCharsets.UTF_8);
        String header = HmacVerifier.sign(body, KEY);
        assertThat(header).startsWith("sha256=");
        assertThat(verifier.verify(header, body)).isTrue();
    }

    @Test
    void verify_accepts_hex_without_prefix() {
        byte[] body = "ping".getBytes(StandardCharsets.UTF_8);
        String header = HmacVerifier.sign(body, KEY);
        String hexOnly = header.substring("sha256=".length());
        assertThat(verifier.verify(hexOnly, body)).isTrue();
    }

    @Test
    void verify_rejects_tampered_body() {
        byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        String header = HmacVerifier.sign(body, KEY);
        byte[] tampered = "{\"x\":2}".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(header, tampered)).isFalse();
    }

    @Test
    void verify_rejects_wrong_key() {
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] otherKey = "another-key-also-must-be-32-bytes-please".getBytes(StandardCharsets.UTF_8);
        String header = HmacVerifier.sign(body, otherKey);
        assertThat(verifier.verify(header, body)).isFalse();
    }

    @Test
    void verify_rejects_null_or_blank_header() {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(null, body)).isFalse();
        assertThat(verifier.verify("", body)).isFalse();
        assertThat(verifier.verify("not-hex", body)).isFalse();
    }

    @Test
    void verify_rejects_truncated_signature() {
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        String header = HmacVerifier.sign(body, KEY);
        // Trim last hex char — длина теперь нечётная, parseHex падает.
        String truncated = header.substring(0, header.length() - 1);
        assertThat(verifier.verify(truncated, body)).isFalse();
    }

    @SuppressWarnings("unused")
    private static SigningKeyPort fixed(byte[] key) {
        return () -> key.clone();
    }
}
