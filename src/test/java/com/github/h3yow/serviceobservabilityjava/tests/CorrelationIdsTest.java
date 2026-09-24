package com.github.h3yow.serviceobservabilityjava.tests;

import com.github.h3yow.serviceobservabilityjava.correlation.CorrelationDecision;
import com.github.h3yow.serviceobservabilityjava.correlation.CorrelationIds;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关联标识归一化规则单测：每个用例打印原始输入与归一化后的判定依据。
 */
class CorrelationIdsTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdsTest.class);

    private CorrelationDecision decideAndLog(String raw) {
        CorrelationDecision decision = CorrelationIds.decide(raw);
        log.info("rawInput={} -> source={} effectiveId={} basis={}",
                raw, decision.source(), decision.id(), decision.reason());
        return decision;
    }

    @Test
    void providedIdIsPreservedVerbatim() {
        CorrelationDecision d = decideAndLog("req-2026.09_24-ABCdef01");
        assertEquals("provided", d.source());
        assertEquals("req-2026.09_24-ABCdef01", d.id());
        assertTrue(d.isValid());
    }

    @Test
    void minAndMaxLengthBoundariesAreAccepted() {
        CorrelationDecision min = decideAndLog("abcdefgh");
        assertEquals("provided", min.source());
        CorrelationDecision max = decideAndLog("a".repeat(64));
        assertEquals("provided", max.source());
    }

    @Test
    void missingHeaderGeneratesDistinguishableId() {
        CorrelationDecision d1 = decideAndLog(null);
        CorrelationDecision d2 = decideAndLog("   ");
        assertEquals("generated", d1.source());
        assertEquals("generated", d2.source());
        assertTrue(d1.id().startsWith("gen-"));
        assertTrue(d2.id().startsWith("gen-"));
        assertNotEquals(d1.id(), d2.id(), "两次生成必须可区分");
        assertFalse(d1.isValid());
    }

    @Test
    void tooShortIsRejected() {
        CorrelationDecision d = decideAndLog("short");
        assertEquals("rejected", d.source());
        assertTrue(d.id().startsWith("gen-"));
        assertTrue(d.reason().contains("length"), "判定依据需说明长度原因");
    }

    @Test
    void tooLongIsRejected() {
        CorrelationDecision d = decideAndLog("a".repeat(65));
        assertEquals("rejected", d.source());
    }

    @Test
    void illegalCharactersAreRejected() {
        CorrelationDecision d = decideAndLog("bad id with spaces");
        assertEquals("rejected", d.source());
        assertTrue(d.reason().contains("illegal characters"));
        CorrelationDecision d2 = decideAndLog("中文标识测试用例");
        assertEquals("rejected", d2.source());
    }

    @Test
    void decisionIsDeterministicForSameInput() {
        CorrelationDecision a = decideAndLog("req-0001");
        CorrelationDecision b = decideAndLog("req-0001");
        assertEquals(a.id(), b.id());
        assertEquals(a.source(), b.source());
    }
}
