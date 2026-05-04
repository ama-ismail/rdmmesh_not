# Handoff — Эпик E1 (Foundation)

> **Аудитория этого документа.** AI-агенты и инженеры, которые подключаются к проекту `rdmmesh` после завершения bootstrap-этапа. Документ самодостаточен — переписки и контекста предыдущей сессии у вас нет, всё что нужно для продолжения — здесь и в [`SPEC.md`](../../SPEC.md).
>
> **Дата handoff'а.** 2026-05-03; обновлено 2026-05-04.
> **Состояние:** E1 закрыт по содержанию SPEC §5.1. End-to-end smoke test (`docker compose up`) пройден 2026-05-04 — Flyway применил 8 миграций, `/healthcheck` зелёный. Остались косметические follow-up'ы (см. §3 ниже).
> **Следующий эпик:** E2 (Identity). Указатели на E2 / E3 / E4 / далее — в §6.

---

## 0. TL;DR за 30 секунд

- Проект — модульный монолит, Java 21 + Dropwizard 4 + JDBI 3 + Flyway + PostgreSQL 16 (см. SPEC §3.1, ADR-0001).
- Структура: `pom.xml` (parent) + 11 Maven-модулей + `rdmmesh-ui` (placeholder) + `bootstrap/sql/migrations/` (Flyway) + `docker/` + `docs/`.
- **Локально нет JDK/Maven.** Сборка идёт через `./bin/mvn`, который запускает `maven:3.9-eclipse-temurin-21` в Docker. Кэш `.m2` живёт в `~/.m2-rdmmesh`.
- **Smoke tests прошедшие:**
  - `./bin/mvn validate` — все 11 модулей резолвятся.
  - `./bin/mvn -DskipITs package` — exit 0, fat jar `rdmmesh-app/target/rdmmesh-service.jar` (25 МБ), миграции лежат внутри jar по путям `db/migration/{module}/V*.sql`.
  - `docker compose -f docker/docker-compose.yml config` — валиден.
  - **`docker compose up` end-to-end (2026-05-04):** оба контейнера healthy, Flyway применил 8 миграций (V001..V070) в одной транзакции, `flyway_schema_history` в `rdmmesh_meta` корректен, в БД 8 schemas (`catalog`, `authoring`, `workflow`, `publishing`, `identity`, `ownership`, `audit`, `rdmmesh_meta`), `GET /healthcheck` отдаёт 200 с тремя зелёными health checks (`info`, `deadlocks`, `rdmmesh`).

---

## 1. Что сделано в E1

### 1.1. Каркас репозитория

| Файл / каталог | Назначение |
|---|---|
| `pom.xml` (parent) | Java 21, BOM Dropwizard 4.0.13, BOM JDBI 3.49, Flyway 10.21, ArchUnit 1.3, jsonschema2pojo 1.2.2, Spotless (palantir-java-format), enforcer-plugin (`requireJavaVersion`, `requireMavenVersion`, `dependencyConvergence`). |
| 11 модульных `pom.xml` | См. §2 ниже — карта модулей. |
| `bin/mvn` | Bash-обёртка: `docker run maven:3.9-eclipse-temurin-21`. Монтирует репо в `/workspace`, кэш `.m2` в `${HOME}/.m2-rdmmesh`, переопределяет `HOME=/tmp/rdmmesh-home`. **Используйте именно его, не системный `mvn`.** |
| `Makefile` | `make help` показывает цели: `build / compile / test / verify / format / format-check / codegen / up / down / logs / psql / ui / clean`. |
| `.mvn/jvm.config`, `.mvn/maven.config` | `-T1C --no-transfer-progress`, `-Xmx1g -XX:+UseG1GC`. |
| `.gitignore`, `.gitattributes`, `.editorconfig` | Стандартные. `eol=lf` для всего, java под 4 пробела, прочее — 2. |
| `docs/adr/0001-modular-monolith-and-stack.md` | Сводное ADR по принятым решениям; на это место указывайте при ссылке на «архитектурное обоснование». |
| `helm/.gitkeep` | Заглушка под Helm chart (V1+). |

### 1.2. JSON Schemas + кодогенерация

`rdmmesh-spec/schema/` — single source of truth для контрактов. Структура:

```
schema/
├── common/                          ← НЕ генерится в POJO, только $ref-таргет
│   ├── types.json                   ← uuid, om_user_id, semver, business_date, instant, localized_label, page_info, key_part…
│   └── enums.json                   ← VersionStatus, CodeItemStatus, HierarchyMode, ReleaseChannel, AssetRole, AssetType, KeyPartType, TransitionAction
├── entity/                          ← одна сущность = один файл
│   ├── domain.json
│   ├── code-set.json
│   ├── code-set-schema.json
│   ├── key-spec.json
│   ├── code-set-version.json
│   ├── code-item.json
│   ├── asset-ownership.json
│   └── user-mapping.json
├── api/                             ← request / response DTO для REST §3.5 SPEC
│   ├── transition-request.json
│   ├── items-page.json
│   ├── version-diff.json
│   ├── bulk-import-result.json
│   ├── webhook-subscription.json
│   └── error-response.json          ← RFC 7807 ProblemDetails
└── events/                          ← payload'ы in-process bus + outbound webhooks + OM webhook
    ├── workflow-transition-event.json
    ├── version-published-event.json
    └── ownership-changed-event.json
```

**Кодогенерация.** `jsonschema2pojo-maven-plugin` (фаза `generate-sources`):
- Цель — `bank.rdmmesh.spec.{entity,api,events}` пакеты.
- `<includes>` ограничен `entity/**`, `api/**`, `events/**` → `common/` доступен только через `$ref`.
- Опции: `useJakartaValidation`, `annotationStyle=jackson2`, `useTitleAsClassname`, `dateTimeType=java.time.OffsetDateTime`, `dateType=java.time.LocalDate`, `useBigDecimals`.
- Результат: 29 `.java` файлов в `rdmmesh-spec/target/generated-sources/jsonschema2pojo/`.

**Если меняете контракт** — правьте JSON Schema и прогоняйте `make codegen` (`./bin/mvn -pl rdmmesh-spec -am generate-sources`). Никаких ручных POJO параллельно — это нарушение ADR-002.

### 1.3. Flyway миграции

Сквозная нумерация `V001..V070` по 7 schemas (Flyway не допускает дубликатов версий между locations). Файлы в `bootstrap/sql/migrations/{module}/V*.sql`, копируются в classpath через `<resource>` в `rdmmesh-app/pom.xml` под `db/migration/{module}/`.

| Файл | Что создаёт |
|---|---|
| `_init/V001__init_extensions_and_meta.sql` | Schema `rdmmesh_meta` (хост `flyway_schema_history`), расширения `pgcrypto`, `pg_trgm`, `btree_gin`, `btree_gist`. Проверяет наличие роли `rdmmesh_app` (warning, не failure). |
| `catalog/V010__catalog_init.sql` | `catalog.domain`, `catalog.code_set`, `catalog.code_set_schema`. Soft-delete на `code_set` (SPEC §5.3 — ingestion может пропустить удаление). Trgm-индексы на `label_*`. |
| `authoring/V020__authoring_init.sql` | `authoring.code_set_version`, `code_set_version_reviewer`, `code_item`, `code_item_closure`. **Bitemporal** envelopes через `daterange`/`tstzrange` с GiST-индексами. JSONB GIN на `attributes` и `parent_key`. tsvector на `label_ru` (russian) и `label_en` (english). CHECK на `status='PUBLISHED' → content_hash IS NOT NULL`. Optimistic lock через `row_version`. |
| `workflow/V030__workflow_init.sql` | `workflow.workflow_transition` (append-only по семантике), `workflow.approval_task` (материализованный «My Tasks»). |
| `publishing/V040__publishing_init.sql` | `publishing.webhook_subscription` (хранит только `secret_id`-pointer в Vault — секрет в БД не лежит), `publishing.webhook_outbox` (transactional outbox с `next_attempt_at` для retry/backoff). |
| `identity/V050__identity_init.sql` | `identity.rdm_user_mapping (om_user_id PK, keycloak_sub UNIQUE, username UNIQUE)`. |
| `ownership/V060__ownership_init.sql` | `ownership.rdm_asset_ownership (asset_id, asset_type, om_user_id, role)` UNIQUE; `is_provisional`, `source_event_id` для идемпотентности webhook'а. |
| `audit/V070__audit_init.sql` | `audit.audit_log`. **Defense-in-depth: триггеры BEFORE UPDATE/DELETE/TRUNCATE с `RAISE EXCEPTION`**, и `REVOKE UPDATE,DELETE,TRUNCATE` для роли `rdmmesh_app`. Подготовлены NULL-able колонки `prev_hash`/`entry_hash` для будущей криптографической цепочки (V14). |

**Грэнты.** Каждый файл оборачивает `GRANT` блок в `DO $$ … IF EXISTS pg_role rdmmesh_app …`, чтобы миграция работала и в окружениях где роль создаётся отдельно (production), и в dev (роль создаётся init-script'ом Postgres-контейнера).

### 1.4. Dropwizard skeleton

```
rdmmesh-app/src/main/java/bank/rdmmesh/app/
├── RdmmeshApplication.java        ← extends Application<RdmmeshConfiguration>; main(). Запускает Flyway (если autoMigrate=true), создаёт JDBI через JdbiFactory, регистрирует InfoHealthCheck. TODO-коммент про регистрацию модулей.
├── RdmmeshConfiguration.java      ← database (DataSourceFactory), flyway (locations, schemas, defaultSchema, autoMigrate). Env-substitution в run-time.
└── health/InfoHealthCheck.java    ← liveness probe.

rdmmesh-app/src/main/resources/
└── config.yml                     ← дефолты для dev. Env-vars: RDM_HTTP_PORT, RDM_ADMIN_PORT, RDM_DB_URL, RDM_DB_USER, RDM_DB_PASSWORD, RDM_FLYWAY_AUTO.

rdmmesh-app/src/test/java/bank/rdmmesh/arch/
└── ModuleIsolationTest.java       ← ArchUnit (3 правила, см. §3 ниже про allowEmptyShould).
```

**Ports + EventBus** в `rdmmesh-api/src/main/java/bank/rdmmesh/api/`:
- `port/WorkflowPort.java` — `transition()`, `history()`, `openTaskFor()`. Свои exceptions: `IllegalStateTransitionException`, `SelfApprovalException`, `InsufficientRoleException`.
- `port/OwnershipPort.java` — `ownersOf()`, `rolesOf()`, `assignProvisionalOwner()`, `applyChangeEvent()`. Record `OwnershipDelta`.
- `port/IdentityPort.java` — `authenticate(jwt)`, `resolveOmUserId()`, `resolveKeycloakSub()`. Record `AuthenticatedUser(omUserId, keycloakSub, username, baseRoles)`.
- `port/SearchPort.java` — `search(Query)`. Records `Hit` и `Query`.
- `port/OutboundPort.java` — `enqueueVersionPublished(VersionPublishedEvent)`. Контракт: persist в outbox в той же транзакции что publish.
- `eventbus/EventBus.java` + `eventbus/DomainEvent.java` — синхронный in-process pub/sub.

**Решение про Flyway.** Используется напрямую через `org.flywaydb.core.Flyway` (без `dropwizard-flyway` модуля) — проще, контролируемо, не требует отдельных CLI-команд. Если переходить к раздельному `db migrate` step в production — добавьте свою `Command` через `bootstrap.addCommand(...)` и сделайте `autoMigrate: false` в prod-config.

### 1.5. Docker Compose dev-стек

```
docker/
├── Dockerfile                          ← multi-stage: build (maven:3.9-eclipse-temurin-21) → runtime (eclipse-temurin:21-jre, non-root user uid 10001, healthcheck на /healthcheck)
├── .dockerignore
├── docker-compose.yml                  ← postgres + rdmmesh-service. Keycloak отложен до E2.
├── README.md                           ← список переменных окружения, команды
└── postgres/init/00-create-app-role.sql  ← создаёт роль rdmmesh_app при пустом datadir
```

**Volume `rdmmesh-postgres-data`** — постоянный. `docker compose down -v` сбрасывает БД.

---

## 2. Карта модулей

| Модуль | Зависит от (прямо) | Owns DB schema | Что добавлять при наполнении |
|---|---|---|---|
| `rdmmesh-spec` | — | — | JSON Schemas (codegen target). |
| `rdmmesh-api` | `rdmmesh-spec`, `jakarta.validation-api` | — | Port-интерфейсы, общие DTO, EventBus. |
| `rdmmesh-catalog` | `rdmmesh-api`, dropwizard-jersey, jdbi3-{core,postgres,jackson2} | `catalog` | DAO + Resources для Domain/CodeSet/CodeSetSchema. **Owner данных** — никто другой write в `catalog` не делает. |
| `rdmmesh-authoring` | `rdmmesh-api`, jersey, jdbi3, networknt json-schema-validator (1.5.5), jackson-dataformat-csv | `authoring` | DAO для CodeSetVersion / CodeItem / closure, bulk-import (CSV), валидация attributes по active CodeSetSchema, diff между версиями. |
| `rdmmesh-workflow` | `rdmmesh-api`, jersey, jdbi3 | `workflow` | Реализация `WorkflowPort` (enum state machine ~200 строк), 4-eyes invariants, approval-tasks. |
| `rdmmesh-publishing` | `rdmmesh-api`, jersey, jdbi3 | `publishing` | Реализация `OutboundPort`, snapshot creation, content_hash (детерминированная JSON serialisation), HMAC signature, transactional outbox + worker. |
| `rdmmesh-distribution` | `rdmmesh-api`, jersey, jdbi3 | — (только READ из `catalog`+`authoring`) | Read-only consumer-facing REST с `as_of`/`knowledge_as_of`, bulk export. **Не делать DB writes** (см. §3 follow-up #2). |
| `rdmmesh-identity` | `rdmmesh-api`, dropwizard-auth, java-jwt, jwks-rsa, caffeine, jdbi3 | `identity` | Реализация `IdentityPort`. JWKS-кэш, OIDC-валидация, lazy lookup в OM REST API при первом логине. |
| `rdmmesh-ownership` | `rdmmesh-api`, jersey, dropwizard-client, jdbi3, caffeine | `ownership` | Реализация `OwnershipPort`. POST `/webhooks/om/ownership` — HMAC проверка, idempotency через `source_event_id`, permission cache invalidation. |
| `rdmmesh-audit` | `rdmmesh-api`, jdbi3 | `audit` (INSERT-ONLY) | Подписка на `EventBus` всех `DomainEvent`, append в `audit_log`. **Не импортировать ничего кроме `rdmmesh-api`** — ArchUnit это проверит. |
| `rdmmesh-app` | все 8 модулей выше + dropwizard-core/jdbi3 + flyway-database-postgresql + postgresql jdbc + archunit + testcontainers (test) | — | Composition root. Регистрация Resources в `Environment.jersey()`. Wiring через явные factory-методы (без DI-контейнеров пока). |

---

## 3. Что осталось доделать в E1 — обязательно перед feature-эпиками

### 3.1. ~~Прогнать `docker compose up` end-to-end~~ ✅ DONE 2026-05-04

Smoke test пройден на свежем volume:
```bash
make up
curl -s http://localhost:8081/healthcheck | jq      # 200, три зелёных healthcheck'а
docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c "\dn"
# → 8 schemas
```

Flyway-лог:
```
Successfully applied 8 migrations to schema "rdmmesh_meta", now at version v070 (execution time 00:00.283s)
```

`postgres:16-alpine` оказался достаточен — расширения `pgcrypto`/`pg_trgm`/`btree_gin`/`btree_gist` подключаются нормально. WARN'ы вида `schema "X" already exists, skipping` — это намеренная idempotency через `CREATE SCHEMA IF NOT EXISTS` в каждой V0X0-миграции (Flyway сам уже создал схему через `createSchemas=true`); WARN'ы безвредные.

### 3.2. Снять `allowEmptyShould(true)` с ArchUnit-правил по мере наполнения модулей

В `rdmmesh-app/src/test/java/bank/rdmmesh/arch/ModuleIsolationTest.java` два правила:
- `audit_only_depends_on_api_or_spec` — снять `allowEmptyShould` сразу как в `rdmmesh-audit/src/main/java` появится первый класс.
- `modules_do_not_import_internals` — снять как только в любом из 8 функциональных модулей появится подпакет `internal`.

В коде `// TODO-комментарии` уже стоят рядом с обеими опциями.

### 3.3. Добавить ArchUnit-правило про "distribution does no DB writes"

SPEC §3.3 явно требует. Сейчас правила нет, потому что модуль пуст. Добавить, как только в `rdmmesh-distribution` появятся первые DAO. Заготовка: запретить вызов `update()/execute()` на `org.jdbi.v3.core.statement.Update`/`StatementContext` из пакета `bank.rdmmesh.distribution..`.

### 3.4. Spotless apply на сгенерированный код модулей

`make format` пройдёт по `src/main/java/**` и `src/test/java/**`. Сейчас нечего форматировать (модули пусты), но первое наполнение лучше начать с `make format-check` в pre-commit.

### 3.5. Первый git commit

В этой сессии **никаких коммитов не сделано** — пользователь не давал явного разрешения. На репо 81 untracked файл (без `target/`). Для следующего агента — спросить пользователя, прежде чем делать первый commit; формулировка коммит-сообщения должна сослаться на SPEC §5.1 эпик E1.

### 3.6. ~~Структурированное логирование (JSON) для prod~~ ✅ DONE 2026-05-04

Подключена зависимость `io.dropwizard:dropwizard-json-logging` в `rdmmesh-app/pom.xml`, создан профиль `rdmmesh-app/src/main/resources/config-prod.yml` с JSON layout (включая `access-json` для request log, `additionalFields.service=rdmmesh`/`env=$RDM_ENV` для tagging'а в агрегаторе). dev `config.yml` — plain text без изменений. В prod также по дефолту `flyway.autoMigrate=false` (миграции — отдельный шаг CI/Helm hook); подтвердить решение с командой эксплуатации перед первым релизом.

### 3.7. ~~CI workflow~~ ✅ DONE 2026-05-04

`.github/workflows/ci.yml` — три job'а:

| Job | Триггер | Что делает |
|---|---|---|
| `validate` | PR + push | `mvn validate` (enforcer + dependencyConvergence) и `mvn -DskipTests compile` (codegen + javac). |
| `verify` | только push в `main`/`release/*` | `mvn -DskipITs verify` (unit + ArchUnit). На failure аплоадит surefire/failsafe-репорты. IT'ы появятся в E2+ через testcontainers. |
| `docker` | только push | `docker/build-push-action` с GHA cache, проверяет multi-stage Dockerfile без push'а в registry. |

Если в банке внутренняя инсталляция GitLab — порт workflow'а в `.gitlab-ci.yml` тривиален (тот же набор шагов).

### 3.8. TS-кодогенерация для UI и Pydantic для коннектора

В E1 НЕ сделано — но логически принадлежит `rdmmesh-spec`:
- TS типы через `json-schema-to-typescript` (для frontend в E11). Скрипт можно положить в `rdmmesh-spec/codegen/typescript/`.
- Pydantic-модели для Python-коннектора `om-rdmmesh-source` (V1, эпик E12). Скрипт `rdmmesh-spec/codegen/pydantic/`.

Оба — это просто npm/pip-вызовы, читающие те же `schema/*.json`. Не настраивайте раньше времени — настройте, когда подойдут E11/E12.

---

## 4. Операционные tips для следующего агента

### 4.1. Сборка

| Хочу | Команда |
|---|---|
| Скомпилировать всё | `make compile` или `./bin/mvn -DskipTests compile` |
| Прогнать unit-тесты | `make test` |
| Полный verify (ArchUnit + IT) | `make verify` |
| Только один модуль (с зависимостями) | `./bin/mvn -pl rdmmesh-catalog -am package` |
| Перегенерировать POJO из JSON Schemas | `make codegen` |
| Проверить форматирование | `make format-check` |
| Применить форматирование | `make format` |
| Поднять dev-стек | `make up` |
| Открыть psql | `make psql` |

### 4.2. Где смотреть конфиги

- Версии библиотек — **только** в `pom.xml` (parent), `<properties>`. Не дублируйте в модульных pom'ах.
- Версии плагинов — там же, в `<pluginManagement>`. Все модули наследуют.
- Dev-конфиг сервиса — `rdmmesh-app/src/main/resources/config.yml`. Env-vars подменяются: `${VAR:-default}`.
- Dev-конфиг compose — `docker/docker-compose.yml`. Env-vars берутся из `docker/.env` (gitignored), смотрите `docker/README.md`.

### 4.3. Подводные камни, на которые я наступил в этой сессии

1. **`bin/mvn` без `HOME=/tmp/...`** — Maven падал с warning «Can not write to /root/.m2/copy_reference_file.log», потому что `-u $(id -u):$(id -g)` уводит uid от root, а HOME остаётся `/root`. Решено в `bin/mvn`: `-e HOME=/tmp/rdmmesh-home -e MAVEN_CONFIG=...`.
2. **Уникальные версии Flyway-миграций между locations.** Flyway падает на дубликатах. Поэтому V001/V010/V020/...; не делайте `V001__catalog_init` и `V001__authoring_init` одновременно.
3. **ArchUnit `failed to check any classes`** — правило с пустым that-таргетом валит build. Используйте `.allowEmptyShould(true)` пока модуль пуст, НО оставьте TODO снять его.
4. **`maven-shade-plugin` warning «files in two or more JARs»** — нормально для fat jar Dropwizard. Убирать руками не нужно. Если станет шумно — добавьте `<filter>` с конкретными артефактами.

---

## 5. Состояние репозитория на момент handoff'а

**81 source-файл** (исключая `target/` и `.git/`). Структура:

```
rdmmesh/
├── SPEC.md                                  (785 строк — главный документ)
├── README.md
├── Makefile
├── pom.xml                                  (parent, 0.1.0-SNAPSHOT)
├── bin/mvn
├── .mvn/{jvm.config,maven.config}
├── .{gitignore,gitattributes,editorconfig,dockerignore}
├── bootstrap/sql/migrations/
│   ├── _init/V001__init_extensions_and_meta.sql
│   ├── catalog/V010__catalog_init.sql
│   ├── authoring/V020__authoring_init.sql
│   ├── workflow/V030__workflow_init.sql
│   ├── publishing/V040__publishing_init.sql
│   ├── identity/V050__identity_init.sql
│   ├── ownership/V060__ownership_init.sql
│   └── audit/V070__audit_init.sql
├── docker/
│   ├── Dockerfile
│   ├── .dockerignore
│   ├── docker-compose.yml
│   ├── README.md
│   └── postgres/init/00-create-app-role.sql
├── docs/
│   ├── README.md
│   ├── adr/0001-modular-monolith-and-stack.md
│   └── handoff/E1-foundation.md             ← вы читаете этот файл
├── helm/.gitkeep                            (placeholder, V1+)
├── rdmmesh-spec/                            (JSON Schema → POJO)
│   ├── pom.xml
│   ├── codegen/.gitkeep
│   └── schema/
│       ├── common/{types.json,enums.json}
│       ├── entity/{8 файлов}
│       ├── api/{6 файлов}
│       └── events/{3 файла}
├── rdmmesh-api/
│   ├── pom.xml
│   └── src/main/java/bank/rdmmesh/api/
│       ├── port/{Workflow,Ownership,Identity,Search,Outbound}Port.java
│       └── eventbus/{DomainEvent,EventBus}.java
├── rdmmesh-catalog/      … 8 функциональных модулей с пустым src/main/java/.gitkeep
├── rdmmesh-authoring/      и собственным pom.xml. Зависимости описаны в §2.
├── rdmmesh-workflow/
├── rdmmesh-publishing/
├── rdmmesh-distribution/
├── rdmmesh-identity/
├── rdmmesh-ownership/
├── rdmmesh-audit/
├── rdmmesh-app/
│   ├── pom.xml
│   ├── src/main/java/bank/rdmmesh/app/
│   │   ├── RdmmeshApplication.java
│   │   ├── RdmmeshConfiguration.java
│   │   └── health/InfoHealthCheck.java
│   ├── src/main/resources/config.yml
│   └── src/test/java/bank/rdmmesh/arch/ModuleIsolationTest.java
└── rdmmesh-ui/                              (placeholder, эпик E11)
```

---

## 6. Указатели на следующие эпики

> Конкретное содержание каждого эпика — в SPEC §5.1. Здесь только то, что ВАЖНО знать на старте.

### E2. Identity (следующий)

- **Что реализовать:** `bank.rdmmesh.api.port.IdentityPort` в модуле `rdmmesh-identity`.
- **Что добавить в compose:** Keycloak-сервис; общий realm с OpenMetadata. Realm-конфиг — JSON в `docker/keycloak/realm-rdmmesh.json` (импорт через `KEYCLOAK_IMPORT`).
- **Что добавить в `RdmmeshApplication.run()`:** `OAuthCredentialAuthFilter`/JWT-аутентификация на основе Dropwizard-auth + jwks-rsa. JWKS-кэш через Caffeine (10 мин TTL).
- **Что заполнить:** `identity.rdm_user_mapping` лениво при первом appearance JWT — REST-вызов в OM API по `preferred_username` для получения `User.id`. Этот вызов — единственное место, где RDM ходит в OM синхронно (см. SPEC §2.4).
- **Что добавить в `RdmmeshConfiguration`:** блок `keycloak: { issuerUri, jwksUri, audience, requiredClaims }` + блок `openmetadata: { baseUrl, botToken }` (для lookup'а user.id).
- **Эпик E2 разблокирует:** E5 (Workflow) — для self-approval-prevention нужен om_user_id из текущего JWT.

### E3. Catalog & Schema

- **Что реализовать:** REST-ресурсы согласно SPEC §3.5 (`/domains`, `/codesets`, `/codesets/{id}/schema`).
- **DAO:** JDBI3 `@SqlObject` интерфейсы в `bank.rdmmesh.catalog.internal.dao` (имя `internal` — чтобы ArchUnit-правило про `internal..` сработало).
- **Bootstrap-владелец:** при создании CodeSet вызывать `OwnershipPort.assignProvisionalOwner(codeset.id, "CODESET", currentUser.omUserId)`.
- **Pojo:** уже сгенерированы в `rdmmesh-spec` (`bank.rdmmesh.spec.entity.CodeSet`, `Domain`, `CodeSetSchema`).

### E4. Authoring

- **Где:** `rdmmesh-authoring`. Самый объёмный модуль.
- **Особенности:** валидация `CodeItem.attributes` через `com.networknt:json-schema-validator` против активной `CodeSetSchema.json_schema`. CSV bulk-import через `jackson-dataformat-csv`. Diff между версиями — view `code_item_diff` (TODO добавить миграцию V021).
- **Optimistic lock:** `code_item.row_version` (column уже есть в миграции V020).

### E5. Workflow

- **Где:** `rdmmesh-workflow`. ~200 строк — enum state machine + JDBI Repository, как заявлено в SPEC §3.1.
- **Жёсткое требование:** `created_by ≠ reviewed_by ≠ approved_by` — реализуется в `WorkflowPort.transition()` через выброс `SelfApprovalException`.
- **No-bypass:** даже у `RDM_ADMIN` нет права `workflow.skip` (SPEC §3.2 #7). Не добавляйте такой shortcut.

### E6. Publishing

- **Где:** `rdmmesh-publishing`. После OWNER_APPROVED — автоматический snapshot.
- **content_hash:** SHA-256 от **детерминированной** JSON serialisation snapshot'а: ключи объектов отсортированы лексикографически, массивы CodeItem отсортированы по `key_parts`. Любой потребитель должен уметь повторить — задокументируйте алгоритм в коде и в `docs/`.
- **HMAC ключ:** через `IdentityPort` или новый `SecretsPort` с подключением к Vault/SOPS. **В БД секрет не хранить**.

### E7. Ownership

- **Где:** `rdmmesh-ownership`. POST `/webhooks/om/ownership`.
- **Идемпотентность:** через `source_event_id` (UNIQUE по `(asset_id, asset_type, om_user_id, role)`).
- **HMAC проверка:** заголовок `X-OM-Signature` (имя из SPEC §2.4); поделитесь секретом с OM Event Subscription.

### E10. Audit

- **Где:** `rdmmesh-audit`. **Подписаться** на все `DomainEvent` через `EventBus.subscribe()`. Insert в `audit.audit_log` через JDBI.
- **Помните:** ArchUnit-правило `audit_only_depends_on_api_or_spec` запрещает импорты из любого другого модуля. Снимите `allowEmptyShould(true)` сразу после первого класса в `rdmmesh-audit/src/main/java`.

### E8 / E9 / E11 / E12 / E13 / E14 — см. SPEC §5.1.

---

## 7. Открытые вопросы / решения, требующие подтверждения от пользователя

Эти моменты в этой сессии не уточнялись — спросите перед тем, как закрывать соответствующие пункты:

1. **Версия Postgres-image.** Сейчас `postgres:16-alpine`. Если `pgcrypto`/`btree_gist` будут проблемными — переключитесь на `postgres:16` (полный Debian-образ).
2. **Production-Strategy для Flyway.** Сейчас `autoMigrate=true` дефолтом. Для prod — отдельный `migrate` step (CI или Helm pre-install hook). Это решение пользователя — обсудите перед E2 catch-up'ом.
3. **HMAC secret rotation policy.** Сколько хранить старые ключи для верификации published-версий? SPEC §3.8 не уточняет.
4. **OM API base URL и bot-токен** для lazy lookup в `IdentityPort` (E2). Спросите при старте E2.
5. **Realm name и client ID для Keycloak.** В SPEC указано «общий с OM realm». Уточните точное имя клиента для rdmmesh.

---

## 8. Версия документа

- **0.1** — 2026-05-03. Создан после завершения E1 (Foundation) bootstrap. Автор предыдущей сессии: Claude Opus 4.7.
- Будущие правки делайте in-place, обновляйте версию и дату.
