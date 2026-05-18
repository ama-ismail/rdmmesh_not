package bank.rdmmesh.workflow.internal.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.Execution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.WorkflowPort;
import bank.rdmmesh.spec.events.WorkflowTransitionEvent;
import bank.rdmmesh.workflow.internal.service.WorkflowService;

/**
 * Flowable-движок workflow (V2 / BR-18, ADR-009 — foundation-слайс).
 *
 * <p>Каждой версии соответствует процесс-инстанс {@code rdm4eyes} с
 * businessKey = {@code versionId}. {@code transition()}:
 * <ol>
 *   <li>находит инстанс по businessKey; если его нет — лениво стартует
 *       (токен встаёт на receive-task {@code rt_await});</li>
 *   <li>ставит переменные и делает {@code trigger} — BPMN прогоняет
 *       service-task {@link WorkflowTransitionDelegate}, который вызывает
 *       <b>существующий</b> {@code WorkflowService.transition} (валидация +
 *       атомарные side-эффекты + event — без изменений);</li>
 *   <li>возвращает event из {@link TransitionResultHolder}.</li>
 * </ol>
 *
 * <p><b>Fallback.</b> Если живого {@code rt_await} нет (процесс достиг
 * терминала после OWNER_APPROVED, либо системный путь publish/deprecate,
 * который вне BPMN) — делегируем напрямую в {@code WorkflowService}: enum-
 * StateMachine остаётся авторитетом легальности и для этих случаев,
 * поведение/коды ошибок 1:1 с дефолтным движком.
 *
 * <p><b>Атомарность.</b> {@code WorkflowService.transition} коммитит свою
 * Postgres-tx (authoring+workflow схемы) отдельно от Flowable-tx
 * ({@code workflow_engine}). Это тот же класс «split-tx», что задокументирован
 * в E5 §1.4: авторитет состояния — {@code authoring.code_set_version} +
 * journal; Flowable-инстанс — топологический трекер. Авторитетный CAS статуса
 * в {@code WorkflowService} ловит конкуренцию (→ IllegalStateTransition),
 * поэтому рассинхрон токена не нарушает корректность.
 */
public final class FlowableWorkflowEngine implements WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(FlowableWorkflowEngine.class);

    private final FlowableEngineManager engine;
    private final WorkflowService service;

    public FlowableWorkflowEngine(FlowableEngineManager engine, WorkflowService service) {
        this.engine = engine;
        this.service = service;
    }

    @Override
    public WorkflowTransitionEvent transition(
            UUID versionId, String targetStatus, UUID actor,
            Set<String> baseRoles, String comment) {

        RuntimeService rt = engine.runtimeService();
        String bk = versionId.toString();

        Execution await = awaitExecution(rt, bk);
        if (await == null) {
            boolean exists = rt.createProcessInstanceQuery()
                    .processInstanceBusinessKey(bk).count() > 0;
            if (exists) {
                // Инстанс есть, но не на rt_await (терминал/иное) — системный
                // путь либо post-terminal: авторитетно через WorkflowService.
                log.debug("flowable: no rt_await for version={} → service fallback", bk);
                return service.transition(versionId, targetStatus, actor, baseRoles, comment);
            }
            // Первый переход версии — лениво стартуем процесс (встанет на rt_await).
            rt.startProcessInstanceByKey(FlowableEngineManager.PROCESS_KEY, bk);
            await = awaitExecution(rt, bk);
            if (await == null) {
                // Теоретически недостижимо (start → rt_await). Не молча
                // деградируем — авторитетный путь.
                log.warn("flowable: started instance but rt_await missing version={} "
                        + "→ service fallback", bk);
                return service.transition(versionId, targetStatus, actor, baseRoles, comment);
            }
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("versionId", bk);
        vars.put("targetStatus", targetStatus);
        vars.put("actor", actor.toString());
        vars.put("baseRoles", baseRoles == null ? "" : String.join(",", baseRoles));
        vars.put("comment", comment);

        TransitionResultHolder.clear();
        try {
            rt.setVariables(await.getId(), vars);
            rt.trigger(await.getId()); // синхронно: delegate → WorkflowService
            WorkflowTransitionEvent event = TransitionResultHolder.get();
            if (event == null) {
                throw new IllegalStateException(
                        "Flowable trigger завершился без результата transition "
                                + "(version=" + bk + ")");
            }
            return event;
        } catch (RuntimeException e) {
            throw unwrap(e);
        } finally {
            TransitionResultHolder.clear();
        }
    }

    private static Execution awaitExecution(RuntimeService rt, String businessKey) {
        return rt.createExecutionQuery()
                .processInstanceBusinessKey(businessKey)
                .activityId(FlowableEngineManager.AWAIT_ACTIVITY)
                .singleResult();
    }

    /**
     * Flowable заворачивает исключение делегата в свой
     * {@code FlowableException}. Достаём оригинал из cause-цепочки, чтобы
     * REST вернул те же коды, что и enum-движок (409/403/404).
     */
    private static RuntimeException unwrap(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof WorkflowPort.SelfApprovalException
                    || t instanceof WorkflowPort.IllegalStateTransitionException
                    || t instanceof WorkflowPort.InsufficientRoleException
                    || t instanceof IllegalArgumentException) {
                return (RuntimeException) t;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return e;
    }
}
