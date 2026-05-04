# rdmmesh — docker dev stack

## Что запускается

- **postgres** — `postgres:16-alpine` с инит-скриптом `postgres/init/00-create-app-role.sql`, создающим runtime-роль `rdmmesh_app`. Схемы и таблицы создаст Flyway при первом запуске сервиса.
- **rdmmesh-service** — multi-stage сборка по `Dockerfile`. Слушает 8080 (API) и 8081 (admin/healthcheck).

Keycloak добавится при подключении JWT-auth (эпик E2).

## Команды

```bash
# Поднять стек:
docker compose -f docker/docker-compose.yml up -d

# Тыкнуть healthcheck:
curl http://localhost:8081/healthcheck

# Заглянуть в БД:
docker compose -f docker/docker-compose.yml exec postgres \
    psql -U rdmmesh_admin -d rdmmesh

# Остановить:
docker compose -f docker/docker-compose.yml down

# Полностью сбросить (удалит все миграции и данные):
docker compose -f docker/docker-compose.yml down -v
```

## Переопределения

Все значения по умолчанию подходят для локальной разработки. Чтобы что-то поменять — кладите `.env` рядом с `docker-compose.yml`. Поддерживаемые переменные:

| Переменная | Дефолт | Назначение |
|---|---|---|
| `RDM_DB_PORT` | `5432` | Порт Postgres на хосте |
| `RDM_DB_NAME` | `rdmmesh` | Имя БД |
| `RDM_DB_ADMIN_USER` | `rdmmesh_admin` | Суперпользователь БД (только для миграций / dev-операций) |
| `RDM_DB_ADMIN_PASSWORD` | `rdmmesh_admin_dev` | Пароль `rdmmesh_admin` |
| `RDM_DB_APP_PASSWORD` | `rdmmesh_dev` | Пароль `rdmmesh_app` (используется сервисом) |
| `RDM_HTTP_PORT` | `8080` | Порт API сервиса |
| `RDM_ADMIN_PORT` | `8081` | Порт admin/health |
