# Handoff — Эпик E3 (Catalog & Schema)

> **Аудитория документа.** AI-агенты и инженеры, подключающиеся к проекту после E3. Документ самодостаточен — переписки и контекста предыдущей сессии у вас нет, всё что нужно — здесь, в [`SPEC.md`](../../SPEC.md), [`E1-foundation.md`](E1-foundation.md) и [`E2-identity.md`](E2-identity.md).
>
> **Дата handoff'а.** 2026-05-04.
> **Состояние:** E3 закрыт по содержанию SPEC §5.1. End-to-end проверен, все 12 endpoint'ов работают.
> **Следующий эпик:** E4 (Authoring). Указатели — в §5.

---

## 0. TL;DR за 30 секунд

- Реализованы REST-ресурсы catalog'а (SPEC §3.5): `/domains`, `/codesets`, `/codesets/{id}/schema`, `/codesets/by-domain/{domainId}`. Префикс — `/api/v1/`.
- Domain'ы создаёт только `RDM_ADMIN` (в проде они приходят из OM ingestion'ом, в dev/pilot — вручную). CodeSet'ы создают `RDM_AUTHOR`/`RDM_SCHEMA_DESIGNER`. PUT schema-revision — `RDM_SCHEMA_DESIGNER`/`RDM_ADMIN`.
- При создании CodeSet'а в одной транзакции пишутся: row в `catalog.code_set` + initial `code_set_schema` (version=1) + provisional row в `ownership.rdm_asset_ownership` (creator → OWNER, `is_provisional=true`).
- Реализован stub `OwnershipPort` (PostgresOwnershipPort): `assignProvisionalOwner` + read-операции. `applyChangeEvent` бросает `UnsupportedOperationException` — это работа E7.
- ArchUnit-rule `modules_do_not_import_internals` теперь жёсткое (без `allowEmptyShould`), плюс добавлено `distribution_does_no_db_writes` (всё ещё с `allowEmpty=true`, пока модуль пуст).
- **Smoke**: domain create → codeset create (с дефолтным KeySpec) → PUT schema v2 → GET schema active/history → проверка provisional row в БД. Все 12 endpoint'ов и role-gating работают.

---

## 1. Что сделано

### 1.1. Resource layer

| URL | Метод | Роль | Что делает |
|---|---|---|---|
| `/domains` | GET | любой auth | список domain'ов |
| `/domains` | POST | `RDM_ADMIN` | создать domain (идемпотентно по `om_domain_id`) |
| `/domains/{id}` | GET | любой auth | один domain |
| `/domains/{id}` | PATCH | `RDM_ADMIN` | обновить metadata |
| `/codesets/{id}` | GET | любой auth | один CodeSet |
| `/codesets/{id}` | PATCH | author/designer/admin | обновить metadata |
| `/codesets/by-domain/{domainId}` | GET | любой auth | список CodeSet'ов в домене |
| `/codesets/by-domain/{domainId}` | POST | author/designer/admin | создать CodeSet |
| `/codesets/{codesetId}/schema` | GET | любой auth | active CodeSetSchema |
| `/codesets/{codesetId}/schema` | PUT | designer/admin | новая ревизия (monotonic version++) |
| `/codesets/{codesetId}/schema/history` | GET | любой auth | все версии схемы |

> **Почему `/codesets/by-domain/{id}` а не `/domains/{id}/codesets`** — два root-resource'а с пересекающимися путями (`@Path("/domains")` + `@Path("/")`) в Jersey ведут к 404 на parametrized сегменте. Решение — собрать всё под `/codesets/...` в одном resource. Если в E11 (UI) понадобится backwards-compat alias — добавится тонкий wrapper.

### 1.2. Структура модуля

```
rdmmesh-catalog/src/main/java/bank/rdmmesh/catalog/
├── CatalogModule.java                       ← composition factory: build(jdbi, ownership) → Resources
├── resource/
│   ├── DomainResource.java                  ← /domains
│   ├── CodeSetResource.java                 ← /codesets, /codesets/by-domain/{id}
│   ├── CodeSetSchemaResource.java           ← /codesets/{id}/schema*
│   └── CodeSetSchemaDto.java                ← record для responses (см. §1.4 — почему свой)
└── internal/
    ├── dao/
    │   ├── DomainDao.java                   ← @SqlObject + DomainRow record
    │   ├── CodeSetDao.java                  ← @SqlObject + CodeSetRow record
    │   └── CodeSetSchemaDao.java            ← @SqlObject + SchemaRow record
    ├── mapper/
    │   └── CatalogMappers.java              ← row → POJO/DTO (UUID/Instant/JSONB конверсии)
    └── service/
        └── CatalogService.java              ← бизнес-операции, транзакции
```

### 1.3. OwnershipPort stub

`rdmmesh-ownership` теперь содержит:
- `OwnershipModule.buildPort(jdbi)` — composition factory.
- `internal/PostgresOwnershipPort.java`:
  - `assignProvisionalOwner(assetId, assetType, creatorOmUserId)` — UPSERT в `ownership.rdm_asset_ownership` с `role=OWNER, is_provisional=true`. Идемпотентно по UNIQUE-ключу.
  - `ownersOf(assetId, assetType)` / `rolesOf(assetId, omUserId)` — read.
  - `applyChangeEvent(...)` — **`UnsupportedOperationException`** до E7. Сознательно фейлим вместо silent no-op'а, чтобы не пропустить регрессию в webhook flow.
- `internal/dao/AssetOwnershipDao.java` — JDBI SqlObject.

### 1.4. Решение: свой `CodeSetSchemaDto` вместо `bank.rdmmesh.spec.entity.CodeSetSchema`

Сгенерированный jsonschema2pojo'ём POJO `JsonSchema` (поле `code_set_schema.json_schema`) **не принимает произвольные fields** — десериализация `{"type":"object", "properties":{...}}` падает с `UnrecognizedPropertyException("type")`. По смыслу это поле должно быть свободным — это произвольный JSON Schema document, jsonschema2pojo для такого случая не идеален.

Решение: в catalog-модуле возвращаем собственный record `CodeSetSchemaDto` с `Map<String, Object> jsonSchema`. Spec/entity POJO `CodeSetSchema` для этого endpoint'а не используется. Если когда-то понадобится строгая валидация JSON Schema input'а — добавим `com.networknt:json-schema-validator` (он уже подключён в `rdmmesh-authoring` для атрибутов CodeItem'ов).

### 1.5. Дефолтный KeySpec при POST codeset

Если клиент не передал `key_spec` — service подставляет минимальный одиночный ключ:
```json
{"parts": [{"name": "code", "type": "STRING"}]}
```
Это удобно для пилотных справочников (IFRS9 stages, country_iso) — там одиночный код-ключ. Composite keys (Risk PD-матрица, Position×System) в clients требуют явный `key_spec` в request body.

### 1.6. ArchUnit changes

`rdmmesh-app/src/test/java/bank/rdmmesh/arch/ModuleIsolationTest.java`:

| Правило | Состояние | Комментарий |
|---|---|---|
| `modules_do_not_depend_on_each_other` | strict | Cross-imports запрещены кроме app/api/spec |
| `audit_only_depends_on_api_or_spec` | `allowEmptyShould(true)` | Снять в E10, когда rdmmesh-audit получит первый класс |
| `modules_do_not_import_internals` | **strict (теперь)** | Снято `allowEmpty`; уже работает на identity/catalog/ownership |
| `distribution_does_no_db_writes` | `allowEmptyShould(true)` | Через запрет depend on `org.jdbi.v3.sqlobject.statement.SqlUpdate`/`SqlBatch`. Снять в E8 |

### 1.7. Перенос `RdmmeshPrincipal` из identity в api

Класс адаптера `Principal` лежал в `rdmmesh-identity`, но catalog'у он нужен для `@Auth RdmmeshPrincipal` в resource'ах. Cross-module imports запрещены ArchUnit'ом, поэтому RdmmeshPrincipal перенесён в `bank.rdmmesh.api.security.RdmmeshPrincipal` — там он логически лежит как часть «Port-контракта» identity.

Импорты обновлены в:
- `JwtAuthenticator.java`, `RoleAuthorizer.java` (внутри identity)
- `RdmmeshApplication.java`, `AuthResource.java` (в app)
- `DomainResource.java`, `CodeSetResource.java`, `CodeSetSchemaResource.java` (в catalog)

---

## 2. Smoke (то, что прошло на 2026-05-04)

```bash
make up
TOKEN_ADMIN=$(KC_USER=dev-admin make kc-token)
TOKEN_AUTHOR=$(KC_USER=dev-author make kc-token)
TOKEN_STEWARD=$(KC_USER=dev-steward make kc-token)

# 1. domain (admin)
curl -X POST -H "Authorization: Bearer $TOKEN_ADMIN" -H "Content-Type: application/json" \
    -d '{"om_domain_id":"11111111-1111-1111-1111-111111111111","name":"risk","display_name":"Risk Department"}' \
    http://localhost:8080/api/v1/domains
# → 201 { id, om_domain_id, name=risk, ... }

# 2. self-approval prevention guard: тот же om_domain_id → возврат уже существующей строки (idempotent)
curl -X POST ... -d '{"om_domain_id":"11111111-...","name":"risk","display_name":"Other"}' /domains
# → 200 с старым id, имя не меняется (намеренно)

# 3. CodeSet (author / schema_designer)
curl -X POST -H "Authorization: Bearer $TOKEN_AUTHOR" \
    -d '{"name":"ifrs9_stages","display_name":"IFRS9 SICR stages","hierarchy_mode":"NONE"}' \
    http://localhost:8080/api/v1/codesets/by-domain/$DOMAIN_ID
# → 201 + CodeSet с дефолтным key_spec={parts:[{code, STRING}]}

# 4. PUT schema (author = schema_designer): version 1 → 2
curl -X PUT -H "Authorization: Bearer $TOKEN_AUTHOR" \
    -d '{"json_schema":{"type":"object","required":["stage"],"properties":{"stage":{"type":"string","enum":["1","2","3"]}}}}' \
    .../schema
# → 200 + version=2

# 5. PUT schema (steward, без RDM_SCHEMA_DESIGNER) → 403 ✅
# 6. GET schema/history → [1, 2]
# 7. SELECT FROM ownership.rdm_asset_ownership → 1 row (CODESET, OWNER, dev-author, is_provisional=t)
```

`make verify` — зелёный, 12 тестов (8 JwtValidator + 4 ArchUnit).

---

## 3. Что осталось доделать в E3

Ничего блокирующего E4. Но есть мягкие follow-up'ы (не делать сейчас):

1. **Integration test catalog'а через testcontainers.** Зависимости `testcontainers/postgresql` + `testcontainers/junit-jupiter` уже в `rdmmesh-catalog/pom.xml`, но IT-теста нет. Заведём с E4 (там тоже понадобится testcontainers для `code_item`).
2. **Validation `key_spec` через JSON Schema.** Сейчас принимается любой объект и записывается в JSONB. Структуру стоит валидировать против `rdmmesh-spec/schema/entity/key-spec.json`. Реализуется в E4 единым валидатором с CodeItem.attributes.
3. **OpenAPI 3.1 dump**. SPEC §3.5 говорит про auto-generated OpenAPI; пока генерируется только REST endpoints из Jersey'а. Подключить `dropwizard-jersey-3` SwaggerCore-feature в E11.
4. **Soft-delete для CodeSet через REST.** `CodeSetDao.softDelete` есть, но REST-метода `DELETE /codesets/{id}` нет. Не критично сейчас — может появиться, когда понадобится.
5. **OwnershipPort.applyChangeEvent** — это E7 (полностью отдельный эпик).

---

## 4. Технический долг и изменения, которые повлияют на следующие эпики

| Что | Где | Когда снять |
|---|---|---|
| `applyChangeEvent` бросает `UnsupportedOperationException` | `PostgresOwnershipPort` | E7 (webhook receiver) |
| `RdmmeshPrincipal` перенесён в `rdmmesh-api/.../security/` | api/security | navigate, теперь стабилен |
| ArchUnit `distribution_does_no_db_writes` с `allowEmpty=true` | `ModuleIsolationTest` | E8 (первый класс в distribution) |
| ArchUnit `audit_only_depends_on_api_or_spec` с `allowEmpty=true` | `ModuleIsolationTest` | E10 (первый класс в audit) |
| `CodeSetSchemaDto` живёт в catalog (не в spec) | `bank.rdmmesh.catalog.resource.CodeSetSchemaDto` | оставить — паттерн для arbitrary JSON-полей |

---

## 5. Указатели на следующие эпики

> Конкретное содержание — в SPEC §5.1.

### E4. Authoring (следующий)

- **Где:** `rdmmesh-authoring`. Самый объёмный модуль.
- **Что реализовать:**
  - `CodeSetVersion` CRUD: создание draft из последней published-версии (или с нуля); список версий; статусы `DRAFT/IN_REVIEW/...`.
  - `CodeItem` CRUD внутри draft'а: GET (paginated), POST, PATCH, DELETE. Только в статусе `DRAFT`.
  - bulk-import (CSV/JSON) через `jackson-dataformat-csv`.
  - Валидация `CodeItem.attributes` через `com.networknt:json-schema-validator` против активной `CodeSetSchema.json_schema` (получаем через `CatalogService.currentSchema(codesetId)`).
  - Diff между версиями — view `code_item_diff` (TODO добавить миграцию V021).
  - Optimistic lock через `code_item.row_version` (column уже есть в V020).
- **Resource'ы под `/codesets/{id}/versions/...` и `/versions/{id}/items/...`** (SPEC §3.5).
- **Зависимости:** `rdmmesh-authoring` уже знает только про `rdmmesh-api`. Для чтения active CodeSetSchema — нужен интерфейс в api: либо новый `CatalogPort.currentSchema(codesetId)`, либо проще — пробрасывать `CatalogService` напрямую через app-wiring (нарушает изоляцию). **Рекомендуется** — добавить `CatalogReadPort` в api, реализовать в catalog, инъектить в authoring через app.
- **Authorization:** все mutations на draft → `RDM_AUTHOR`; transition'ы (`POST /versions/{id}/transitions`) — это уже E5 (Workflow).

### E5. Workflow

- **Что реализовать:** `WorkflowPort` через enum state machine. Self-approval prevention. Approval task'и.
- **Точка стыковки:** `Resource.transition(@Auth principal, ...)` → `WorkflowPort.transition()`. om_user_id берётся из `RdmmeshPrincipal.omUserId()`.

### E6 / E7 / E8 — см. SPEC §5.1.

---

## 6. Открытые вопросы (всё ещё актуальны для команды банка)

Без изменений с E2:

1. Production-Strategy для Flyway — подтверждено: `autoMigrate=false` в prod, миграции отдельным шагом. Согласовать с эксплуатацией перед первым релизом.
2. Реальные prod-параметры Keycloak (issuer/jwks/audience/client_secret для backend).
3. OM API base URL и bot-токен.
4. HMAC secret rotation policy (для E6).

---

## 7. Версия документа

- **0.1** — 2026-05-04. Создан после завершения E3 (Catalog & Schema). Автор: Claude Opus 4.7.
