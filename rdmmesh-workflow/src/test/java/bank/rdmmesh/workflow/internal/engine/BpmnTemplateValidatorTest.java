package bank.rdmmesh.workflow.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Контракт per-domain BPMN-шаблона (V2 / BR-18 round 2). Чистый unit —
 * Flowable BPMN-конвертер на classpath, без БД (гоняется локально).
 */
final class BpmnTemplateValidatorTest {

    private static byte[] bpmn(String body) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:flowable=\"http://flowable.org/bpmn\""
                + " targetNamespace=\"http://rdmmesh.bank/wf\">"
                + "<process id=\"custom_proc\" isExecutable=\"true\">"
                + body
                + "</process></definitions>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static final String AWAIT = "<receiveTask id=\"rt_await\"/>";
    private static final String DELEGATE =
            "<serviceTask id=\"svc\" flowable:delegateExpression=\"${rdmTransitionDelegate}\"/>";

    @Test
    void validTemplateReturnsProcessKey() {
        BpmnTemplateValidator.Contract c =
                BpmnTemplateValidator.validate(bpmn(AWAIT + DELEGATE));
        assertThat(c.processKey()).isEqualTo("custom_proc");
    }

    @Test
    void missingAwaitReceiveTaskRejected() {
        assertThatThrownBy(() -> BpmnTemplateValidator.validate(bpmn(DELEGATE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rt_await");
    }

    @Test
    void missingDelegateServiceTaskRejected() {
        assertThatThrownBy(() -> BpmnTemplateValidator.validate(bpmn(AWAIT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rdmTransitionDelegate");
    }

    @Test
    void nonBpmnRejected() {
        assertThatThrownBy(() -> BpmnTemplateValidator.validate(
                        "not xml at all".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyRejected() {
        assertThatThrownBy(() -> BpmnTemplateValidator.validate(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пуст");
    }

    @Test
    void sha256IsHex64() {
        String h = BpmnTemplateValidator.sha256Hex("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(h).hasSize(64).matches("[a-f0-9]{64}");
    }
}
