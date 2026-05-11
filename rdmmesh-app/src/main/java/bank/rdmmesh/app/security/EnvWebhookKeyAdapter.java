package bank.rdmmesh.app.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

import bank.rdmmesh.api.port.WebhookKeyPort;

/**
 * Реализация {@link WebhookKeyPort} поверх переменных окружения. По pointer'у
 * {@code secret_id} subscription'а (см. SPEC §3.5 webhook-subscription.json,
 * V040.publishing.webhook_subscription.secret_id) ищет ключ в env по правилу:
 *
 * <pre>
 *   key = System.getenv("RDM_WEBHOOK_KEY_" + UPPER(secretId))
 * </pre>
 *
 * <p>В dev любой secretId резолвится в общий {@code RDM_WEBHOOK_KEY_DEFAULT} либо в
 * fallback из конфига; это упрощает smoke. В prod при появлении Vault — менять
 * целиком адаптер на {@code VaultWebhookKeyAdapter}, без правок в worker'е.
 *
 * <p>SPEC §3.7 запрещает хранение HMAC-ключа в БД — поэтому ровно как у
 * {@link EnvSigningKeyAdapter}: только env / Vault / SOPS.
 */
public final class EnvWebhookKeyAdapter implements WebhookKeyPort {

    /** Минимальная длина ключа (соглашение проекта — параллельно SPEC HMAC). */
    public static final int MIN_KEY_BYTES = 32;

    private static final String VAR_PREFIX = "RDM_WEBHOOK_KEY_";
    private static final String FALLBACK_VAR = VAR_PREFIX + "DEFAULT";

    private final byte[] devFallback;

    public EnvWebhookKeyAdapter(byte[] devFallback) {
        if (devFallback == null) {
            this.devFallback = null;
        } else if (devFallback.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "dev fallback webhook key должен быть не короче " + MIN_KEY_BYTES + " байт");
        } else {
            this.devFallback = devFallback.clone();
        }
    }

    public static EnvWebhookKeyAdapter withDevFallback(String fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return new EnvWebhookKeyAdapter(fallback.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public byte[] resolveKey(String secretId) {
        if (secretId == null || secretId.isBlank()) {
            throw new IllegalArgumentException("secret_id не может быть пустым");
        }
        String specific = System.getenv(VAR_PREFIX + secretId.toUpperCase(Locale.ROOT));
        if (specific != null && !specific.isBlank()) {
            return ensureLength(specific.getBytes(StandardCharsets.UTF_8), secretId);
        }
        String defaultVar = System.getenv(FALLBACK_VAR);
        if (defaultVar != null && !defaultVar.isBlank()) {
            return ensureLength(defaultVar.getBytes(StandardCharsets.UTF_8), "default");
        }
        if (devFallback != null) {
            return devFallback.clone();
        }
        throw new IllegalArgumentException(
                "no webhook key for secret_id=" + secretId
                        + " (env " + VAR_PREFIX + secretId.toUpperCase(Locale.ROOT)
                        + " not set, и нет dev fallback'а)");
    }

    private static byte[] ensureLength(byte[] key, String label) {
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "webhook key для '" + label + "' короче "
                            + MIN_KEY_BYTES + " байт (фактически " + key.length + ")");
        }
        return key;
    }
}
