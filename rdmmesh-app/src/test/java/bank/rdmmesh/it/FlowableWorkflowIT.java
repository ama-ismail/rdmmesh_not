package bank.rdmmesh.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.api.port.VersionLifecyclePort;
import bank.rdmmesh.api.port.WorkflowPort;
import bank.rdmmesh.app.eventbus.SyncEventBus;
import bank.rdmmesh.authoring.AuthoringModule;
import bank.rdmmesh.catalog.CatalogModule;
import bank.rdmmesh.ownership.OwnershipModule;
import bank.rdmmesh.workflow.WorkflowModule;
import bank.rdmmesh.workflow.internal.engine.FlowableEngineManager;
import bank.rdmmesh.workflow.internal.engine.FlowableWorkflowEngine;
import bank.rdmmesh.workflow.internal.service.WorkflowService;

/**
 * V2 / BR-18 (ADR-009) — доказывает, что <b>Flowable-движок реально
 * приводит в действие</b> дефолтный 4-eyes и при этом инварианты
 * (self-approval / role-gate / атомарный journal) сохранены: BPMN-процесс
 * лишь оркеструет, а валидацию и side-эффекты делает существующий
 * {@code WorkflowService} (без рефакторинга бизнес-логики, SPEC ADR-004).
 *
 * <p>Сценарий: submit → (author пытается steward_approve → 409
 * SelfApproval, токен на месте) → steward_approve → owner_approve.
 * Проверки: финальный статус OWNER_APPROVED; в {@code workflow_transition}
 * ровно 3 строки в хронологии; reviewer и approved_by зафиксированы тем же
 * аудированным путём, что у enum-движка.
 *
 * <p>Flowable создаёт свои ACT_*-таблицы сам ({@code databaseSchemaUpdate})
 * в схеме {@code workflow_engine} (Flyway V031 создал схему+гранты). Локально
 * {@code Skipped} (Docker-Desktop, E14.9 §2); авторитетный прогон — CI.
 */
final class FlowableWorkflowIT extends PostgresIT {

    private static WorkflowService workflowService() {
        Jdbi jdbi = appJdbi();
        VersionLifecyclePort lifecycle = AuthoringModule.buildLifecyclePort(jdbi);
        OwnershipPort ownership = OwnershipModule.buildPort(jdbi);
        CatalogReadPort catalog = CatalogModule.buildReadPort(jdbi);
        EventBus bus = new SyncEventBus();
        // service() из Resources — тот же WorkflowService, что и enum-путь.
        return WorkflowModule.build(jdbi, lifecycle, ownership, catalog, bus).service();
    }

    private static UUID seedDraft(UUID author, String sfx) throws SQLException {
        UUID versionId = UUID.randomUUID();
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            UUID domainId = UUID.randomUUID();
            UUID codesetId = UUID.randomUUID();
            st.execute("INSERT INTO catalog.domain (id, om_domain_id, name) VALUES ('"
                    + domainId + "', '" + UUID.randomUUID() + "', 'risk_" + sfx + "')");
            st.execute("INSERT INTO catalog.code_set "
                    + "(id, domain_id, name, key_spec, created_by) VALUES ('"
                    + codesetId + "', '" + domainId + "', 'cs_" + sfx
                    + "', '{}'::jsonb, '" + author + "')");
            st.execute("INSERT INTO authoring.code_set_version "
                    + "(id, codeset_id, version, status, schema_version, created_by) VALUES ('"
                    + versionId + "', '" + codesetId + "', '0.1.0-draft', 'DRAFT', 1, '"
                    + author + "')");
        }
        return versionId;
    }

    private static String status(UUID v) throws SQLException {
        try (Connection c = adminConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT status FROM authoring.code_set_version WHERE id='" + v + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = adminConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void flowableEngineDrivesFullFourEyesAndKeepsInvariants() throws SQLException {
        UUID author = UUID.randomUUID();
        UUID steward = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID v = seedDraft(author, "flw");

        WorkflowService service = workflowService();
        FlowableEngineManager manager = new FlowableEngineManager(
                appJdbcUrl(), appUser(), appPassword(), service);
        try {
            FlowableWorkflowEngine engine = new FlowableWorkflowEngine(manager, service);

            // 1. submit (DRAFT→IN_REVIEW): лениво стартует процесс, trigger →
            //    delegate → WorkflowService.
            engine.transition(v, "IN_REVIEW", author, Set.of("RDM_AUTHOR"), null);
            assertThat(status(v)).isEqualTo("IN_REVIEW");

            // 2. self-approval: автор пытается steward_approve. StateMachine
            //    бросает ДО role-gate; Flowable откатывает trigger, токен на
            //    rt_await, статус не меняется.
            assertThatThrownBy(() -> engine.transition(
                            v, "STEWARD_APPROVED", author, Set.of("RDM_STEWARD"), null))
                    .isInstanceOf(WorkflowPort.SelfApprovalException.class);
            assertThat(status(v)).as("после отката статус не изменился")
                    .isEqualTo("IN_REVIEW");

            // 3. steward_approve (IN_REVIEW→STEWARD_APPROVED) — другой юзер.
            engine.transition(v, "STEWARD_APPROVED", steward, Set.of("RDM_STEWARD"), null);
            assertThat(status(v)).isEqualTo("STEWARD_APPROVED");

            // 4. owner_approve (STEWARD_APPROVED→OWNER_APPROVED) — терминал BPMN.
            engine.transition(v, "OWNER_APPROVED", owner, Set.of("RDM_OWNER"), null);
            assertThat(status(v)).isEqualTo("OWNER_APPROVED");

            // Аудит/инварианты — тем же путём, что enum-движок.
            assertThat(count("SELECT count(*) FROM workflow.workflow_transition "
                    + "WHERE version_id='" + v + "'"))
                    .as("3 перехода: submit, steward_approve, owner_approve")
                    .isEqualTo(3);
            assertThat(count("SELECT count(*) FROM authoring.code_set_version_reviewer "
                    + "WHERE version_id='" + v + "' AND om_user_id='" + steward + "'"))
                    .as("steward зафиксирован как reviewer").isEqualTo(1);
            try (Connection c = adminConnection();
                    Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT approved_by FROM authoring.code_set_version WHERE id='"
                                    + v + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(owner.toString());
            }
        } finally {
            manager.stop(); // закрыть движок + его JDBC-пул (singleton-БД делится)
        }
    }
}
