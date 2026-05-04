# ADR-0001. Modular monolith on the OpenMetadata stack

- **Status.** Accepted (initial — bootstrap of the repository).
- **Date.** 2026-05-03.
- **Reference.** SPEC §3.1, §3.3, §4.3 (ADR-001 … ADR-008 in narrative form).

## Context

`rdmmesh` is being built next to OpenMetadata 1.12 as the bank's RDM solution.
The team is small (one squad), the immediate scope is two pilot domains
(Risk/IFRS9 and Security/Access Matrix), and the system must be operationally
co-located with OM (shared Keycloak realm, similar UX, mirrored persistence
patterns).

## Decision

1. **Modular monolith, eight bounded contexts** — one JVM, one deploy, eight
   Maven modules with strict isolation enforced by ArchUnit. The most likely
   future split is `distribution` (read-heavy).
2. **Java 21 + Dropwizard 4 + JDBI3 + Flyway + PostgreSQL 16** — same stack
   patterns as OM, so the team can fork its `EntityRepository` shape and avoid
   reinvention.
3. **Schema-first via JSON Schema** in `rdmmesh-spec/`, codegen for Java POJOs
   and TS types from a single source of truth.
4. **Postgres FTS** with `russian`+`english` dictionaries and `pg_trgm` behind
   a `SearchPort` — defer Elasticsearch until volumes justify it.
5. **Enum state machine** behind a `WorkflowPort` — defer Flowable until V2
   when custom BPMN per domain becomes a real requirement.
6. **Loose coupling with OpenMetadata** — pull-only ingestion from OM into RDM
   metadata catalogue (via the separate `om-rdmmesh-source` connector), and a
   single asynchronous webhook from OM to RDM for ownership changes. RDM never
   calls OM API on the synchronous path.
7. **Bitemporal and composite keys from day one** in the schema, even when the
   UI does not yet expose them — adding either retroactively is painful.

## Consequences

- Three external production components only: Postgres, Keycloak, the JVM
  service. No ES, Kafka, Redis, Iceberg, Airflow on the RDM side.
- ArchUnit enforces module isolation at compile-time test phase. New developers
  cannot accidentally couple modules.
- `Ports & Adapters` for `SearchPort`, `WorkflowPort`, `OutboundPort`,
  `IdentityPort`, `OwnershipPort` keeps the V2 migration paths cheap.
- Bootstrap-period quirk: a CodeSet has a provisional owner (its creator) until
  the OM ingestion + ownership webhook round-trip completes. Publish is not
  blocked on this; the audit log records `owner_was_provisional=true`.

## Alternatives considered

- **Microservices from day one.** Rejected — operational overhead is not paid
  back at current team and volume scale.
- **Spring Boot.** Rejected — less alignment with the OM codebase, no Dropwizard
  patterns reuse.
- **Existing PIM/MDM tools (Pim Core, AtroCore).** Rejected — see SPEC §1.1.
