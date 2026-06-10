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

## Что сделано (Stage 2-final — relational driven нормальным flow'ом)

Цель стадии: завязать обычный authoring-flow на relational store, чтобы
`__draft`/`__current` наполнялись без ручных `/relational/...` вызовов.

| Часть | Где |
|------|-----|
| Live draft-mirror | `RelationalStoreService.mirrorUpsertItem`/`mirrorDeleteItem`/`mirrorClearDraft` — точечное зеркало item'а в `__draft` (lazy provision через `ensureProvisioned`, ключи позиционно из `keyParts`) |
| Dual-write | `AuthoringService` получил nullable `RelationalStoreService`; `addItem`/`updateItem` зеркалят из готового `CodeItemDto`, `deleteItem` — по `keyParts`, `clearAllItems` — clear, bulk — `syncFromVersion` (upsert-only пачка). Всё **best-effort** в отдельной tx через `mirror(...)` — сбой зеркала не роняет `code_item` |
| Publish-хук | `RelationalStoreService.registerOn(EventBus)` + `onVersionPublished` — подписка на `VersionPublishedDomainEvent` (тот же bus, что у publishing). После реального publish'а (E6) post-commit пересобирает `__current`: `syncFromVersion` (бэкфилл draft из `code_item`) + `publish`. Best-effort (SPEC §3.8) |
| Wiring | `AuthoringModule.build` создаёт один `RelationalStoreService`, регистрирует на `eventBus` и отдаёт в `AuthoringService` |
| Тест | `RelationalStoreServiceTest` — pure-гарантии, что `onVersionPublished` никогда не бросает (null/blank/bad uuid/backend-error) |

**Ключевое решение — почему через `EventBus`, а не внутрь publish-tx.** `PublishingService`
эмитит `VersionPublishedDomainEvent` уже **после** commit'а основной tx. Реляционная
пересборка идёт отдельной tx, best-effort — так сбой/баг зеркала не может зароллбэчить
реальный publish (важно: локально ITs не гоняются, цена регрессии высока). `code_item`
остаётся источником истины и на publish'е заново «кормит» relational через `syncFromVersion`,
поэтому любое расхождение live-зеркала самоисцеляется на publish'е.

## Что сделано (Stage 4-lite — недостающие колонки под read-path)

Перед read-path'ом (Stage 3) дорастили обе таблицы недостающими полями `code_item`:

- `RelationalDdlBuilder.STANDARD` += `description_ru`/`description_en` (text),
  `parent_key` (**jsonb**-массив key-part'ов, без self-FK — это Stage 6), `order_index` (integer).
- `RelationalDdlBuilder.addColumnsIfNotExists` — идемпотентный `ALTER TABLE … ADD COLUMN
  IF NOT EXISTS` (без NOT NULL — упал бы на непустой таблице; DEFAULT переносится).
  `provision` зовёт его для обеих таблиц → эволюция схемы без ручного drop.
- `RelationalStoreService`: `syncFromVersion`-SELECT + `ItemRow` + `toCells` и
  `mirrorUpsertItem`/`itemCells` тянут новые поля; `AuthoringService.mirrorUpsert` отдаёт их из DTO.
- `publish` (INSERT…SELECT по `dataColumns`) подхватывает новые колонки без правок.

Чего пока НЕТ (остаётся в Stage 4-full): bitemporal `system_from`/`system_to`, closure/
cycle-detection, `parent_ref`, per-row `content_hash`. `__current` по-прежнему = один
последний PUBLISHED-снапшот (без произвольных semver/`knowledge_as_of`).

## Что сделано (Stage 3 — read-path → CodeItemDto)

Проекция строк `rd_data` обратно в канонический `CodeItemDto` (динамический SELECT → DTO) —
доказательство, что relational store воспроизводит API-контракт `code_item` целиком.

- `RelationalStoreService.projectRow` — **pure static** (column→value map → `CodeItemDto`):
  key-колонки → `key_parts`; не-ключевые/не-стандартные → `attributes`; `parent_key`
  парсится из jsonb-текста; `id`/`system_*`/`row_version`/`parent_ref` = null (нет в модели).
  Тестируется без БД (`RelationalStoreServiceTest.projectRow_*`).
- `RelationalDdlBuilder.standardColumnNames()` — единый источник имён стандартных колонок
  (классификация attribute vs standard в проекции).
- `listCurrentItems(codesetId)` / `listDraftItems(versionId)` — SELECT * + `ORDER BY
  order_index NULLS LAST, <ключи>` → `projectRow`.
- REST (read-only, additive — рядом с `code_item`, не заменяя): `GET …/items`
  (PUBLISHED-снапшот), `GET …/draft-items?version_id=` (черновик).

**Почему additive, а не hard-switch `CodeItemResource`/distribution:** relational store
пока best-effort зеркало (source-of-truth — `code_item`); жёсткое переключение
production-чтения = риск отдать неполные данные при пропуске зеркала. Эндпоинты `/items`
доказывают round-trip rd_data → канонический DTO; переключение основного чтения — Stage 7.
Ограничение проекции: jsonb-атрибуты (object/array) приходят как JSON-текст.

## Что сделано (Stage 4-full — bitemporal + иерархия)

**Stage 4a — bitemporal + parent_ref.** `STANDARD` += `system_from`/`system_to`
(timestamptz, зеркало `code_item.system_*`) и `parent_ref` (jsonb, cross-codeset ссылка).
Sync/mirror/projection тянут их; `projectRow` отдаёт `system_*` строками и `parent_ref`
как Map (`parseMap`). Теперь `__draft`/`__current` несут **весь** набор полей `code_item`.

**Stage 4b — closure + cycle-detection (on-demand, без материализации).** В
`RelationalDdlBuilder` — чистые генераторы рекурсивного SQL (тестируются без БД):

- `closureQuery` — `WITH RECURSIVE` по `parent_key`: пары `(ancestor_key, descendant_key,
  depth)`, self-reflexive + walk вверх, лимит глубины 32 (как триггерный V022).
  `self_key = jsonb_build_array(<ключевые колонки>)` — та же форма, что в `parent_key`
  ребёнка, поэтому join сравнивает jsonb напрямую.
- `cycleDetectionQuery` — нативный `CYCLE self_key SET is_cycle USING path` (PG 14+):
  ключи, участвующие в цикле (аналог триггерной проверки V023, on-demand).
- `RelationalStoreService.currentClosure`/`draftClosure`/`currentCycles`/`draftCycles`
  исполняют их и парсят jsonb-ключи в `List<String>` (`ClosureRow`).
- REST (read-only): `GET …/closure[?version_id=]`, `GET …/cycles[?version_id=]`
  (без `version_id` — по `__current`, с ним — по `__draft` версии).

Отличие от `code_item`: closure **не материализуется** (нет per-table closure-таблицы +
триггеров) — вычисляется запросом. Для спайка это проще и достаточно; материализация —
если упрётся в производительность. Рекурсивный SQL локально не прогоняется (нет БД) —
покрыт pure-тестами на структуру, исполнение валидирует CI.

Чего всё ещё НЕТ: per-row `content_hash`/детерминированная сериализация и колоночный
`DiffCalculator` — это **Stage 5**.

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

- ~~**Stage 2-final — единственный источник**~~ ✅ **СДЕЛАНО** (см. раздел выше): dual-write
  на `__draft` из normal flow + publish-хук на пересборку `__current`. Остаётся как
  best-effort зеркало поверх ведущего `code_item`; «жёсткое» переключение source-of-truth —
  Stage 7.
- ~~**Stage 3 — read-path**~~ ✅ **СДЕЛАНО** (additive, см. раздел выше): проекция
  `rd_data` → `CodeItemDto`, эндпоинты `/items` и `/draft-items`. Hard-switch
  `CodeItemResource`/distribution на rd_data — Stage 7.
- ~~**Stage 4-full — bitemporal/hierarchy**~~ ✅ **СДЕЛАНО** (см. раздел выше): `system_*`,
  `parent_ref` (4a) + on-demand closure и cycle-detection по `parent_key` (4b). Материализация
  closure-таблицы и per-row `content_hash` вынесены в Stage 5 / при необходимости.
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
