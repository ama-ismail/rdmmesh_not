# Handoff — Эпик E2 (Identity)

> **Аудитория этого документа.** AI-агенты и инженеры, которые подключаются к проекту `rdmmesh` после завершения E2. Документ самодостаточен — переписки и контекста предыдущей сессии у вас нет, всё что нужно для продолжения — здесь, в [`SPEC.md`](../../SPEC.md) и [`E1-foundation.md`](E1-foundation.md).
>
> **Дата handoff'а.** 2026-05-04.
> **Состояние:** E2 закрыт по содержанию SPEC §5.1. End-to-end JWT-флоу проверен (см. §0).
> **Следующий эпик:** E3 (Catalog & Schema). Указатели — в §6.

---

## 0. TL;DR за 30 секунд

- Подключён OIDC через Keycloak 26 (realm `bank` с clients `rdmmesh-backend` + `rdmmesh-ui`).
- Реализован `bank.rdmmesh.api.port.IdentityPort` (`KeycloakIdentityPort` в `rdmmesh-identity`):
  - JWT валидация (`auth0/java-jwt` 4.4.0, RS256) с проверкой signature/iss/aud/exp/required-claims;
  - JWKS-кэш на Caffeine (TTL 10 мин по умолчанию);
  - DAO `UserMappingDao` (JDBI3 SqlObject) для `identity.rdm_user_mapping`;
  - lazy lookup `om_user_id` через REST OpenMetadata; при недоступности OM — fallback на deterministic UUID v5 (`provisional`).
- Dropwizard-auth wiring: `OAuthCredentialAuthFilter` + `JwtAuthenticator` + `RoleAuthorizer` + `@RolesAllowed`-feature.
- **Smoke tests:**
  - `JwtValidatorTest` — 8 unit-тестов (signature/iss/aud/exp/required-claim/missing-kid/wrong-kid/non-uuid-sub) ✅
  - End-to-end JWT флоу через docker compose:
    - `GET /api/v1/auth/me` без токена → 401 ✅
    - `GET /api/v1/auth/me` с валидным токеном → 200 + JSON `{omUserId, keycloakSub, username, baseRoles}` ✅
    - `GET /api/v1/auth/me` с битым токеном → 401 ✅
    - в БД появляется row в `identity.rdm_user_mapping` (provisional UUID v5, потому что OM в dev не настроен) ✅

---

## 1. Что сделано в E2

### 1.1. Keycloak в dev-стеке

| Файл | Что |
|---|---|
| `docker/docker-compose.yml` | Сервис `keycloak` (`quay.io/keycloak/keycloak:26.0`) с `start-dev --import-realm`, h2 in-memory store. Порт 8090 на хосте. Зависимости: `rdmmesh-service` ждёт keycloak'a через `condition: service_started` (healthcheck в minimal-image без curl/wget сделать ровно сложно). |
| `docker/keycloak/realms/realm-bank.json` | Realm `bank`. Clients: `rdmmesh-backend` (confidential, service-account для bot-lookup'а в OM) + `rdmmesh-ui` (public, PKCE, redirect к `localhost:5173`/`localhost:3000`). Group'ы `RDM_*`, default group `RDM_CONSUMER`. Тестовые users: `dev-admin`/`dev-author`/`dev-steward`/`dev-owner` с паролем `dev`. Audience-mapper в обоих clients проставляет `aud=rdmmesh-backend`. |
| `docker/README.md` | URL'ы, env-vars, команды. |
| `Makefile` | `make kc-token` (получить JWT для dev-юзера), `make kc-admin` (печатает URL'ы), `make psql` (исправлен на `rdmmesh_admin`). |

### 1.2. Конфиг

| Файл | Что |
|---|---|
| `rdmmesh-app/src/main/java/.../KeycloakConfig.java` | `issuerUri`, `jwksUri`, `audience`, `usernameClaim`, `groupsClaim`, `requiredClaims`, `jwksCacheTtl`, `clockSkew`. Duration-поля типа `io.dropwizard.util.Duration` (чтобы парсить `10 minutes`/`60 seconds`), getter возвращает `java.time.Duration`. |
| `rdmmesh-app/src/main/java/.../OpenMetadataConfig.java` | `baseUrl`, `botToken`, `connectTimeout`, `requestTimeout`. Опциональный (пустой baseUrl → OM lookup отключён). |
| `rdmmesh-app/src/main/resources/config.yml` | dev-блоки `keycloak` (с дефолтами под compose) и `openmetadata` (пустой по умолчанию). |
| `rdmmesh-app/src/main/resources/config-prod.yml` | prod-блоки: `RDM_KC_*` обязательны, OM тоже (без него нет real `om_user_id`). |

**Issuer/JWKS split.** В compose: `issuerUri = http://localhost:8090/realms/bank` (Keycloak ставит `iss` по `Host`-хедеру, UI ходит через localhost:8090), `jwksUri = http://keycloak:8080/realms/bank/protocol/openid-connect/certs` (загрузка JWKS происходит изнутри docker network через alias `keycloak`). Это разные URL для разных целей: один валидируется как claim, другой используется для HTTP. Подробности — в `docker/README.md`.

### 1.3. rdmmesh-identity модуль

```
rdmmesh-identity/src/main/java/bank/rdmmesh/identity/
├── IdentityModule.java                      ← composition factory (builder)
├── RdmmeshPrincipal.java                    ← Principal-adapter над AuthenticatedUser
├── JwtAuthenticator.java                    ← Dropwizard Authenticator<String, RdmmeshPrincipal>
├── RoleAuthorizer.java                      ← Authorizer для @RolesAllowed
└── internal/
    ├── KeycloakIdentityPort.java            ← реализация IdentityPort
    ├── dao/
    │   └── UserMappingDao.java              ← JDBI SqlObject DAO + UserMappingRow record
    ├── jwt/
    │   ├── JwksKeyResolver.java             ← Caffeine-кэш over UrlJwkProvider
    │   └── JwtValidator.java                ← signature/iss/aud/exp/required-claims
    └── om/
        └── OpenMetadataUserClient.java      ← минимальный OM REST client (Java HttpClient)
```

**Решения:**

- **Provisional UUID v5 (fallback при отсутствии OM).** `KeycloakIdentityPort` генерирует deterministic UUID v5 от `(rdm_namespace, keycloak_sub)` если OM-клиент не настроен или вернул 404. Запись попадает в `rdm_user_mapping` сразу — реальная reconciliation (UPDATE на `om_user_id` после появления OM) допустима SPEC §2.4. Namespace UUID прибит в коде (`c5b1a4e1-…`).
- **Cache в `KeycloakIdentityPort`.** Aутентификационный кэш по `keycloak_sub` (Caffeine, 10k items, без TTL) — токены коротко-живущие, sub стабилен, БД-lookup лишний.
- **`groupsClaim` берётся из конфига**, по умолчанию `groups`. AD-группы в realm-bank.json мапятся 1:1 в realm-роли через `oidc-group-membership-mapper`.
- **OM-клиент — голый Java HttpClient**, без Dropwizard-client / Jersey-client / RestEasy. Один endpoint, один use case.

### 1.4. Wiring в RdmmeshApplication

```java
public void run(RdmmeshConfiguration config, Environment environment) {
    // ...
    Jdbi jdbi = new JdbiFactory().build(...);
    jdbi.installPlugin(new SqlObjectPlugin());    // нужно для DAO в identity и далее в catalog/...

    IdentityPort identityPort = buildIdentityPort(jdbi, config);
    registerJwtAuth(environment, identityPort);

    environment.jersey().register(new AuthResource());      // GET /api/v1/auth/me
    // ...
}
```

`registerJwtAuth` подключает `OAuthCredentialAuthFilter`, `AuthValueFactoryProvider.Binder<RdmmeshPrincipal>` и `RolesAllowedDynamicFeature` — после этого `@Auth RdmmeshPrincipal principal` работает на любом resource'е.

### 1.5. /api/v1/auth/me — smoke endpoint

`bank.rdmmesh.app.auth.AuthResource` — простой GET, требует валидный JWT, возвращает identity текущего пользователя. Полезен и в проде (фронт может звать его сразу после OIDC login flow для синка), и на старте — единственный способ убедиться, что auth-pipeline собран целиком.

---

## 2. Что не делалось намеренно

- **Реальная интеграция с OpenMetadata.** В dev стенде OM нет, lookup отключён (`RDM_OM_BASE_URL` пустой). При появлении prod-стенда нужно прописать env-vars и проверить `OpenMetadataUserClient.findUserIdByName()` против реального OM API (`/api/v1/users/name/{username}`).
- **Service-account flow для bot'а.** Client `rdmmesh-backend` — confidential с `serviceAccountsEnabled=true` и dev-secret `dev-backend-secret` в realm-JSON. Прод-секрет должен прийти из Vault/SOPS. Service-account нужен будет в E12 (ingestion-коннектор) и для самого bot'а в OM lookup.
- **HMAC verify-endpoint** для published-версий (`GET /versions/{id}/verify` из SPEC §3.8) — относится к E6, не E2.
- **Self-approval prevention** — реализуется в E5 (Workflow), не здесь.

---

## 3. Что проверить, прежде чем стартовать E3

### 3.1. Полный verify

```bash
make verify    # все модули, юнит-тесты + ArchUnit
```

Должно быть зелёное для всех 12 модулей.

### 3.2. End-to-end JWT-флоу

```bash
make up
TOKEN=$(make kc-token)         # KC_USER=dev-author по умолчанию
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/auth/me | jq
# → {"omUserId":"...", "keycloakSub":"...", "username":"dev-author", "baseRoles":["RDM_AUTHOR","RDM_SCHEMA_DESIGNER"]}

docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c \
    "SELECT username, om_user_id, last_seen_at FROM identity.rdm_user_mapping;"
```

### 3.3. ArchUnit `internal..` rule

В `rdmmesh-identity/src/main/java/bank/rdmmesh/identity/internal/` появились классы — это значит, что правило `modules_do_not_import_internals` в `ModuleIsolationTest.java` теперь не «пустое» для как минимум одного модуля. Можно убрать `allowEmptyShould(true)` с этого правила (см. handoff E1 §3.2). Сделать первым шагом E3, чтобы регрессии ловились с самого начала.

### 3.4. Снять `allowEmptyShould(true)` с `audit_only_depends_on_api_or_spec` — **только** когда в `rdmmesh-audit/src/main/java/` появится первый класс (в E10).

---

## 4. Указатели на следующие эпики

> Конкретное содержание каждого эпика — в SPEC §5.1. Здесь только то, что ВАЖНО знать на старте.

### E3. Catalog & Schema (следующий)

- **Что реализовать:** REST-ресурсы `/domains`, `/codesets`, `/codesets/{id}/schema` (SPEC §3.5).
- **Где:** `rdmmesh-catalog`. Создать структуру `bank.rdmmesh.catalog.{internal/dao,internal/service,resource}` (`internal..` нужен для ArchUnit-правила).
- **DAO:** JDBI3 `@SqlObject` интерфейсы — паттерн уже задан в `rdmmesh-identity/.../UserMappingDao.java`.
- **Bootstrap-владелец:** при создании CodeSet вызывать `OwnershipPort.assignProvisionalOwner(codeset.id, "CODESET", currentUser.omUserId)`. Owner ещё не реализован (E7), временно можно положить заглушку с TODO.
- **POJO:** уже сгенерированы в `rdmmesh-spec/target/generated-sources/jsonschema2pojo/bank/rdmmesh/spec/entity/{CodeSet,Domain,CodeSetSchema}.java`.
- **Защита эндпоинтов:** `@Auth RdmmeshPrincipal principal` для read, `@RolesAllowed("RDM_SCHEMA_DESIGNER")` для PUT schema, `@RolesAllowed("RDM_AUTHOR")` для PATCH metadata.

### E4. Authoring

- **Где:** `rdmmesh-authoring`. Самый объёмный модуль.
- **Особенности:** валидация `CodeItem.attributes` через `com.networknt:json-schema-validator` против активной `CodeSetSchema.json_schema`. CSV bulk-import через `jackson-dataformat-csv`. Diff между версиями — view `code_item_diff` (TODO добавить миграцию V021).
- **Optimistic lock:** `code_item.row_version` (column уже есть в миграции V020).

### E5. Workflow

- **Где:** `rdmmesh-workflow`. ~200 строк — enum state machine + JDBI Repository, как заявлено в SPEC §3.1.
- **Self-approval prevention:** `created_by ≠ reviewed_by ≠ approved_by` — реализуется в `WorkflowPort.transition()` через выброс `SelfApprovalException`. ID берутся из `RdmmeshPrincipal.omUserId()` — этот метод теперь работает.

### E6 / E7 / E8 / далее — см. SPEC §5.1 и handoff E1 §6.

---

## 5. Открытые вопросы / решения, требующие подтверждения

1. **Имя namespace для provisional UUID v5.** Сейчас прибито в коде (`c5b1a4e1-7c00-4e2c-9c8b-2c0c2c8a6f10`). Если потребуется reconciliation между prod-стендами и dev — namespace должен быть фиксирован раз и навсегда. Не менять без миграции данных.
2. **Production-Strategy для Flyway.** Подтверждено ранее: `autoMigrate=false` в prod, миграции — отдельный шаг. Зафиксировано в `config-prod.yml`. Нужно подтвердить с командой эксплуатации перед первым релизом.
3. **HMAC secret rotation policy.** Ещё не уточнено, релевантно для E6 (Publishing).
4. **Реальные prod-параметры Keycloak.** issuer/jwks/audience прописаны через env-vars, конкретные значения зависят от prod-Keycloak'а банка.
5. **OM API base URL и bot-токен.** Нужно при появлении prod-OM. До этого — provisional UUID работает корректно.

---

## 6. Версия документа

- **0.1** — 2026-05-04. Создан после завершения E2 (Identity). Автор: Claude Opus 4.7.
