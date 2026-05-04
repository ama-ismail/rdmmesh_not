-- Bootstrap script — runs once on a fresh postgres data volume (mounted by docker-entrypoint).
-- Creates the runtime application role used by rdmmesh-service. Schemas and tables
-- come later through Flyway migrations.

CREATE ROLE rdmmesh_app WITH LOGIN PASSWORD 'rdmmesh_dev';
GRANT CONNECT ON DATABASE rdmmesh TO rdmmesh_app;

-- Allow rdmmesh_app to use the schema where Flyway will create the
-- migration history. Schemas are created by Flyway itself with createSchemas=true,
-- so we only need a temporary CREATE on database for that to work.
GRANT CREATE ON DATABASE rdmmesh TO rdmmesh_app;
