package bank.rdmmesh.workflow;

import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.api.port.VersionLifecyclePort;
import bank.rdmmesh.api.port.WorkflowJournalPort;
import bank.rdmmesh.api.port.WorkflowPort;
import bank.rdmmesh.workflow.internal.PostgresWorkflowJournalPort;
import bank.rdmmesh.workflow.internal.PostgresWorkflowPort;
import bank.rdmmesh.workflow.internal.service.WorkflowService;
import bank.rdmmesh.workflow.resource.MyTasksResource;
import bank.rdmmesh.workflow.resource.WorkflowTransitionResource;

/**
 * Composition factory для {@code rdmmesh-workflow}. Возвращает контейнер с двумя
 * REST-resource'ами и реализацией {@link WorkflowPort} — composition root приложения
 * регистрирует resource'ы в Jersey, а порт прокидывает в audit/publishing по мере
 * наполнения соответствующих модулей.
 */
public final class WorkflowModule {

    private WorkflowModule() {}

    public static Resources build(
            Jdbi jdbi,
            VersionLifecyclePort lifecycle,
            OwnershipPort ownership,
            CatalogReadPort catalog,
            EventBus eventBus) {
        WorkflowService service = new WorkflowService(jdbi, lifecycle, ownership, catalog, eventBus);
        return new Resources(
                service,
                new PostgresWorkflowPort(service),
                new WorkflowTransitionResource(service),
                new MyTasksResource(service));
    }

    /** Тонкий append-only порт для системных post-hoc transitions (publish/deprecate из E6). */
    public static WorkflowJournalPort buildJournalPort(Jdbi jdbi) {
        return new PostgresWorkflowJournalPort(jdbi);
    }

    public record Resources(
            WorkflowService service,
            WorkflowPort port,
            WorkflowTransitionResource transitions,
            MyTasksResource myTasks) {}
}
