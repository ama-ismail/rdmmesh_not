# rdmmesh

Reference Data Management для подхода Data Mesh, согласованный с OpenMetadata 1.12.x.

> **Полное описание системы — в [`SPEC.md`](./SPEC.md).** Этот файл — короткая выжимка для разработчика.

## Что это

`rdmmesh` — система ведения жизненного цикла бизнес-справочников банка (CodeSets). Закрывает то, что OpenMetadata намеренно не делает: создание, версионирование, согласование (4-eyes), bitemporal-хранение и публикацию справочников бизнес-доменами без участия IT.

**Граница ответственности.** RDM — только lifecycle и дистрибуция справочников. Discovery / lineage / governance / ownership остаются в OpenMetadata. RDM не делает MDM (matching, golden record).

## Архитектура одной строкой

Modular monolith, Java 21 + Dropwizard 4 + JDBI3 + Flyway, PostgreSQL 16, React 18 + AntD. Восемь bounded contexts (`catalog`, `authoring`, `workflow`, `publishing`, `distribution`, `identity`, `ownership`, `audit`), изолированных ArchUnit-тестами и общающихся через интерфейсы из `rdmmesh-api`.

## Структура репозитория

| Путь | Назначение |
|---|---|
| `rdmmesh-spec/` | JSON Schema всех сущностей и API (single source of truth) + конфигурация кодогенерации |
| `rdmmesh-api/` | Общие DTO и Port-интерфейсы (`SearchPort`, `WorkflowPort`, `OutboundPort`, `IdentityPort`, `OwnershipPort`) |
| `rdmmesh-catalog/` | Метаданные CodeSet, CodeSetSchema, домены |
| `rdmmesh-authoring/` | Drafts, CodeItem CRUD, импорт, валидация |
| `rdmmesh-workflow/` | State machine, переходы, approval-задачи |
| `rdmmesh-publishing/` | Snapshot, content hash, HMAC-подпись |
| `rdmmesh-distribution/` | Read-only REST для consumer'ов |
| `rdmmesh-identity/` | JWT, маппинг AD-групп → ролей |
| `rdmmesh-ownership/` | Webhook от OM, `rdm_asset_ownership`, `rdm_user_mapping` |
| `rdmmesh-audit/` | Append-only журнал |
| `rdmmesh-app/` | Dropwizard `Application`, конфигурация, бутстрап |
| `rdmmesh-ui/` | React + TS + AntD frontend |
| `bootstrap/sql/migrations/` | Flyway миграции (по schema на bounded context) |
| `docker/` | `Dockerfile`, `docker-compose.yml` для dev |
| `helm/` | Helm chart для продуктивного деплоя |
| `docs/adr/` | Architecture Decision Records |

Отдельный артефакт — Python-коннектор `om-rdmmesh-source` (живёт в собственном репо), деплоится в OM Airflow.

## Быстрый старт (dev-окружение)

> Требуется только Docker. JDK/Maven локально не нужны — сборка идёт в multi-stage образе.

```bash
docker compose -f docker/docker-compose.yml up -d   # postgres, keycloak, rdmmesh-service
```

Пользовательский UI разрабатывается отдельно:

```bash
cd rdmmesh-ui
npm install
npm run dev
```

## Дорожная карта

См. SPEC §5.2:
- **MVP** (~3 мес.) — пилот Risk/IFRS9, минимальный UI, ручная регистрация в OM.
- **V1** (+3 мес.) — Security/Access Matrix, ingestion-коннектор, distribution `as_of`, иерархии, outbound webhooks.
- **V2** (+6 мес.) — Custom BPMN, Kafka, ES, dbt source generation.

## Принципы

См. SPEC §3.2 — schema-first, modular monolith с явными bounded contexts, Ports & Adapters, слабая связанность с OpenMetadata, immutability published-версий, append-only audit, no-bypass workflow, bitemporal с первого дня, composite keys с первого дня.
