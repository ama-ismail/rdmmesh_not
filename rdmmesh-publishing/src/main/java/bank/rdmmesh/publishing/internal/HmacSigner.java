package bank.rdmmesh.publishing.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import bank.rdmmesh.api.port.SigningKeyPort;

/**
 * SHA-256 + HMAC-SHA-256 helper'ы для published-версий (SPEC §3.8).
 *
 * <pre>
 *   content_hash       = sha256_hex(canonical_bytes)
 *   approval_signature = hmac_sha256_hex(content_hash || approver_id || iso8601, key)
 * </pre>
 *
 * Любой потребитель может перепроверить content_hash, перечитав canonical bytes;
 * для подписи нужен HMAC-ключ — её делаем server-side в verify-endpoint.
 *
 * <p>Конкатенатор подписи использует {@code "|"}-разделители — байты detect-able
 * визуально, и модель явная: ни {@code content_hash}, ни UUID не содержат {@code |}.
 */
public final class HmacSigner {

    private final SigningKeyPort keys;

    public HmacSigner(SigningKeyPort keys) {
        this.keys = keys;
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing in JRE", e);
        }
    }

    /**
     * approval_signature для версии. Формула фиксирована на E6 — менять = ломать
     * verify прежних версий.
     */
    public String signApproval(String contentHash, String approverOmUserId, String iso8601Timestamp) {
        String payload = contentHash + "|" + approverOmUserId + "|" + iso8601Timestamp;
        return hmacSha256Hex(payload.getBytes(StandardCharsets.UTF_8), keys.currentHmacKey());
    }

    private static String hmacSha256Hex(byte[] payload, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return toHex(mac.doFinal(payload));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
