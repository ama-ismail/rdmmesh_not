# Spike — Relational CodeSets (отказ от JSONB)

## Идея

Вместо generic-хранилища с JSONB (`authoring.code_item.key_parts`/`attributes`,
`catalog.code_set_schema.json_schema`) каждый справочник материализуется в
**реальную типизированную таблицу** в схеме `rd_data`: по колонке на каждую
key-part и на каждый атрибут, строки — настоящие строки «ячейка за ячейкой».

Это ветка `spike/relational-codesets`. Решение по объёму — **полная замена
storage** (см. вопрос заказчику), безопасно делается стадиями. Модель версионности —
**вариант C**: на справочник две таблицы (draft + current).

## Модель версионности (вариант C)

На справочник — две физические таблицы в `rd_data`, производные от базового имени
`<domain>__<codeset>` (≤54 символов, чтобы суффиксы укладывались в лимит идентификатора PG 63):

- **`"<base>__draft"`** — рабочая область авторинга. PK `(version_id, <ключи>)`; все
  черновики сосуществуют строками, различаются по `version_id`.
- **`"<base>__current"`** — текущий PUBLISHED-снапшот. PK `(<ключи>)` — то, на что
  можно вешать **настоящие FK** (Stage 6) и что отдаётся потребителям/distribution.

Write-path пишет в `__draft` по `version_id`; на **publish** `__current` атомарно
пересобирается из draft нужной версии (`DELETE` + `INSERT ... SELECT`).

## Что сделано (Stage 1 + Stage 2)

| Часть | Где |
|------|-----|
| Миграция | `V024__relational_store.sql` — `rd_data` + grants(`CREATE`) + реестр `codeset_physical_table` (base name + `published_version_id`) |
| Порт | `CatalogReadPort.CodeSetSnapshot.keySpecJson` (+ `CatalogReadAdapter`) |
| Типы | `internal/relational/RelationalTypes` — key-part / JSON Schema → SQL-тип (pure) |
| DDL | `internal/relational/RelationalDdlBuilder` — base/draft/current имена, `createTableWithPk`, `withStandard`, валидация идентификаторов (pure) |
| Сервис | `internal/relational/RelationalStoreService` — `provision` (draft+current), `upsertDraftRow`/`deleteDraftRow`, `syncFromVersion` (бэкфилл code_item→draft), `publish` (пересборка current), `listCurrentRows`/`listDraftRows` |
| DAO | `internal/dao/PhysicalTableRegistryDao` (+ `setPublishedVersion`) |
| REST | `resource/RelationalCodeSetResource` — `provision`, `sync`, `draft-rows` (POST/DELETE/GET), `publish`, `rows` (current) |
| Тест | `RelationalDdlBuilderTest` (типы + DDL + draft/current, pure) |

### Маппинг типов

key-part: STRING→text, INTEGER→bigint, NUMBER→double precision, BOOLEAN→boolean,
DATE→date, DATETIME→timestamptz, UUID→uuid.
JSON Schema property: то же + enum→text, object/array→jsonb, string+format(date/
date-time/uuid)→date/timestamptz/uuid.

### Форма таблиц

```sql
CREATE TABLE rd_data."<base>__draft" (
    "version_id" uuid NOT NULL,
    "<keypart>"  <type> NOT NULL, ...           -- по одной на key-part
    "<attr>"     <type>, ...                     -- по одной на атрибут
    "label_ru" text, "label_en" text,
    "status" text NOT NULL DEFAULT 'ACTIVE',
    "effective_from" date, "effective_to" date,
    PRIMARY KEY ("version_id", "<keypart>", ...)
);
CREATE TABLE rd_data."<base>__current" ( /* те же колонки без version_id */
    PRIMARY KEY ("<keypart>", ...)               -- цель настоящих FK (Stage 6)
);
```

Безопасность: имена идентификаторов валидируются snake_case-паттерном, значения
биндятся параметрами с `CAST(:p AS <type>)` — конкатенации значений в SQL нет.

## Как потрогать

```bash
make up        # Flyway применит V024 (rd_data + реестр)
T=$(make kc-token)
CS=<codeset-uuid>;  V=<draft-version-uuid>
# 1) создать обе таблицы (draft + current) из key_spec + схемы
curl -X POST -H "Authorization: Bearer $T" localhost:8080/api/v1/relational/codesets/$CS/provision
# 2a) залить существующие items версии в draft (бэкфилл из jsonb)
curl -X POST -H "Authorization: Bearer $T" "localhost:8080/api/v1/relational/codesets/$CS/sync?version_id=$V"
# 2b) или записать строку черновика «ячейка за ячейкой»
curl -X POST -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"branch_id":"001","branch_sgmnt_id":42,"label_ru":"Отделение 001"}' \
  "localhost:8080/api/v1/relational/codesets/$CS/draft-rows?version_id=$V"
# 3) опубликовать → пересборка __current из draft версии
curl -X POST -H "Authorization: Bearer $T" "localhost:8080/api/v1/relational/codesets/$CS/publish?version_id=$V"
# 4) прочитать published-снапшот
curl -H "Authorization: Bearer $T" localhost:8080/api/v1/relational/codesets/$CS/rows
# 5) убедиться, что это реальные таблицы
make psql   # \dt rd_data.*   и   SELECT * FROM rd_data."<base>__current";
```

## Дальше — оставшиеся стадии

- **Stage 2-final — единственный источник**: завязать `AuthoringService.addItem`/`patch`/
  bulk на `__draft` (вместо/в дополнение к `authoring.code_item`), и `publish` workflow'а
  на пересборку `__current`. Сейчас relational write-path работает как самостоятельный
  путь через `/relational/...`, code_item ещё ведущий.
- **Stage 3 — read-path**: `CodeItemResource` GET/листинг + distribution читают из
  `rd_data` (динамический SELECT → DTO).
- **Stage 4 — bitemporal/hierarchy**: `effective_*`/`system_*`, closure-таблица и
  cycle-detection на реляционной модели (или отдельные служебные таблицы).
- **Stage 5 — publish/diff/hash**: детерминированная сериализация строк физической
  таблицы для `content_hash`/подписи; `DiffCalculator` на колонках.
- **Stage 6 — FK**: `column_refs` (E25) → реальные `ALTER TABLE ... ADD FOREIGN KEY`
  между `rd_data`-таблицами (после материализации цели).
- **Stage 7**: удалить JSONB-колонки/индексы из `code_item`/`code_set_schema` и снести
  generic-путь.

## Риски / открытые вопросы

- **Версионность**: выбран вариант C (draft + current). `__current` хранит только
  текущий PUBLISHED; полная история версий — в `__draft` по `version_id`. Если нужна
  bitemporal-история на стороне потребления — расширять отдельно (Stage 4).
- **Эволюция схемы**: добавление/удаление атрибута → `ALTER TABLE ADD/DROP COLUMN`
  обеих таблиц (миграция данных). Сейчас `provision` только `CREATE TABLE IF NOT EXISTS`.
- **Динамический DDL под прод-ролью**: `rdmmesh_app` получил `CREATE` на `rd_data` —
  оценить с точки зрения безопасности (отдельная роль/schema-allowlist).
- **Лимит имени**: базовое имя `<domain>__<codeset>` ≤ 54 (чтобы `__current`/`__draft`
  укладывались в 63); длинные имена → нужен хеш-суффикс.
- **publish без транзакционной координации с workflow**: relational `publish` сейчас
  отдельный вызов; в Stage 2-final его надо встроить в реальный publish-переход.
