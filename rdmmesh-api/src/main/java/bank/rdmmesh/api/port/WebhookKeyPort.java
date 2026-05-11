package bank.rdmmesh.api.port;

/**
 * Резолвит {@code secret_id} subscription'а (Vault path / SOPS key id, SPEC §3.5,
 * `webhook-subscription.json`) в HMAC-ключ доставки. Сам ключ в БД не хранится —
 * там лежит только pointer; реальное значение приходит из Vault/SOPS-адаптера в проде.
 *
 * <p>На пилоте — env-адаптер ({@code RDM_WEBHOOK_KEY_<UPPER(secretId)>}); fallback
 * на общий dev-ключ задаётся на уровне адаптера. Замена реализации не требует правок
 * в worker'е доставки.
 *
 * <p>Параллельно к {@link SigningKeyPort} (который отдаёт серверный HMAC-ключ для
 * подписи snapshot'ов) — этот порт отдаёт per-subscription ключ для подписи payload'а
 * outbound webhook'а. Разные ключи, разные политики ротации.
 */
public interface WebhookKeyPort {

    /**
     * Возвращает ключ (≥32 байт) для подписи payload'а outbound webhook'а.
     * Бросает {@link IllegalArgumentException} если secret_id неизвестен —
     * это считается мисконфигурацией subscription'а, а не транзиентной ошибкой доставки.
     */
    byte[] resolveKey(String secretId);
}
