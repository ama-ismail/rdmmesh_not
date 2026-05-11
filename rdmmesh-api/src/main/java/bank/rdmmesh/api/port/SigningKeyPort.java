package bank.rdmmesh.api.port;

/**
 * Поставщик HMAC-ключа для подписи published-версий (SPEC §3.7 — ключи живут в
 * Vault/SOPS, не в БД). На пилоте реализация — env-var адаптер; в проде — Vault.
 * Замена адаптера не требует изменений в бизнес-логике publishing.
 *
 * <p>Сам ключ запрашивается каждым вызовом — rotation policy ещё не зафиксирована
 * (open question в handoff'ах). При появлении rotation — расширить порт методом
 * {@code allKnownKeys()} (для verify старых версий с прежним ключом).
 */
public interface SigningKeyPort {

    /**
     * Текущий HMAC-ключ для {@code approval_signature} published-версий.
     * Не короче 32 байт. На пилоте — env {@code RDM_HMAC_KEY}.
     */
    byte[] currentHmacKey();
}
