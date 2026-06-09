# Spike — Relational CodeSets (отказ от JSONB)

## Идея

Вместо generic-хранилища с JSONB (`authoring.code_item.key_parts`/`attributes`,
`catalog.code_set_schema.json_schema`) каждый справочник материализуется в
**реальную типизированную таблицу** в схеме `rd_data`: по колонке на каждую
key-part и на каждый атрибут, строки — настоящие строки «ячейка за ячейкой».

Это ветка `spike/relational-codesets`. Решение по объёму — **полная замена
storage** (см. вопрос заказчику), но безопасно она делается стадиями. Эта ветка —
**Stage 1**: рабочий реляционный слой (DDL-генерация + типизированный CRUD + REST),
поверх которого можно поэтапно вытеснять JSONB из остальных путей.

## Что в Stage 1

| Часть | Где |
|------|-----|
| Миграция | `bootstrap/sql/migrations/authoring/V024__relational_store.sql` — схема `rd_data` + grants (вкл. `CREATE`) + реестр `authoring.codeset_physical_table` |
| Порт | `CatalogReadPort.CodeSetSnapshot.keySpecJson` (+ заполнение в `CatalogReadAdapter`) — авторингу нужен `key_spec` для ключевых колонок |
| Типы | `internal/relational/RelationalTypes` — key-part / JSON Schema → SQL-тип (pure) |
| DDL | `internal/relational/RelationalDdlBuilder` — `CREATE TABLE`, имя таблицы `<domain>__<codeset>`, валидация идентификаторов (pure) |
| Сервис | `internal/relational/RelationalStoreService` — `provision` (DDL+реестр), `upsertRow` (cell-by-cell, `CAST(:p AS type)` + ON CONFLICT), `listRows` |
| DAO | `internal/dao/PhysicalTableRegistryDao` |
| REST | `resource/RelationalCodeSetResource` — `POST /relational/codesets/{id}/provision`, `POST .../rows`, `GET .../rows` |
| Тест | `RelationalDdlBuilderTest` (типы + DDL, pure) |

### Маппинг типов

key-part: STRING→text, INTEGER→bigint, NUMBER→double precision, BOOLEAN→boolean,
DATE→date, DATETIME→timestamptz, UUID→uuid.
JSON Schema property: то же + enum→text, object/array→jsonb, string+format(date/
date-time/uuid)→date/timestamptz/uuid.

### Форма таблицы

```sql
CREATE TABLE rd_data."<domain>__<codeset>" (
    "<keypart>"  <type> NOT NULL,        -- по одной на key-part
    ...
    "<attr>"     <type>,                 -- по одной на атрибут схемы
    ...
    "label_ru"   text,
    "label_en"   text,
    "status"     text NOT NULL DEFAULT 'ACTIVE',
    "effective_from" date,
    "effective_to"   date,
    PRIMARY KEY ("<keypart>", ...)
);
```

Безопасность: имена идентификаторов валидируются snake_case-паттерном
(`^[a-z][a-z0-9_]{0,63}$`), значения биндятся параметрами с `CAST(:p AS <type>)` —
конкатенации значений в SQL нет.

## Как потрогать

```bash
make up        # поднять стек (Flyway применит V024, создаст rd_data + реестр)
T=$(make kc-token)
CS=<codeset-uuid>
# 1) материализовать таблицу из key_spec + активной схемы
curl -X POST -H "Authorization: Bearer $T" localhost:8080/api/v1/relational/codesets/$CS/provision
# 2) записать строку «ячейка за ячейкой»
curl -X POST -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"branch_id":"001","branch_sgmnt_id":42,"label_ru":"Отделение 001"}' \
  localhost:8080/api/v1/relational/codesets/$CS/rows
# 3) прочитать строки
curl -H "Authorization: Bearer $T" localhost:8080/api/v1/relational/codesets/$CS/rows
# 4) убедиться, что это реальная таблица
make psql   # \dt rd_data.*   и   SELECT * FROM rd_data."<domain>__<codeset>";
```

## Дальше — стадии полной замены (НЕ в этой ветке)

- **Stage 2 — write-path**: `AuthoringService.addItem`/`patch`/bulk пишут в физическую
  таблицу вместо `authoring.code_item`. Версии: таблица на `(codeset, version)` либо
  колонка `version_id` + партиционирование. Решить модель версионности.
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

- **Версионность**: code_item versioned по `version_id`; физической таблице нужна
  стратегия (таблица-на-версию vs. колонка версии). Самый крупный вопрос Stage 2.
- **Эволюция схемы**: добавление/удаление атрибута → `ALTER TABLE ADD/DROP COLUMN`
  (миграция данных). Сейчас `provision` только `CREATE TABLE IF NOT EXISTS`.
- **Динамический DDL под прод-ролью**: `rdmmesh_app` получил `CREATE` на `rd_data` —
  оценить с точки зрения безопасности (отдельная роль/schema-allowlist).
- **Лимит имени**: `<domain>__<codeset>` ≤ 63 символов; длинные имена → нужен хеш-суффикс.
