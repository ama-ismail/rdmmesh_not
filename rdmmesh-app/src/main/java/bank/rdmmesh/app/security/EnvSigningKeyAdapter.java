package bank.rdmmesh.app.security;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import bank.rdmmesh.api.port.SigningKeyPort;

/**
 * Реализация {@link SigningKeyPort} поверх переменных окружения. На пилоте — единственная
 * реализация; в проде её заменит Vault/SOPS-адаптер. SPEC §3.7 явно запрещает хранить
 * HMAC-ключ в БД, поэтому источником на dev-стенде остаётся env.
 *
 * <p>Имя env-var и фолбэк зашиты в дефолте конфига; никаких "12345"-значений в коде —
 * пустой ключ должен валить старт сервиса (см. {@link #fromEnv(String, String)}).
 */
public final class EnvSigningKeyAdapter implements SigningKeyPort {

    private final byte[] key;

    public EnvSigningKeyAdapter(byte[] key) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException(
                    "HMAC key должен быть не короче 32 байт (RDM_HMAC_KEY)");
        }
        this.key = key.clone();
    }

    public static EnvSigningKeyAdapter fromEnv(String envVarName, String fallbackForDev) {
        String value = System.getenv(envVarName);
        if (value == null || value.isBlank()) {
            value = fallbackForDev;
        }
        Objects.requireNonNull(value, "no value for " + envVarName);
        return new EnvSigningKeyAdapter(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public byte[] currentHmacKey() {
        return key.clone();
    }
}
