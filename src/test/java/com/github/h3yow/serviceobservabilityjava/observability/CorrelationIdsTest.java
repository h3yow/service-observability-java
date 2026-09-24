package com.github.h3yow.serviceobservabilityjava.observability;

import com.github.h3yow.serviceobservabilityjava.observability.correlation.CorrelationIds;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 关联标识校验规则单测：每条用例打印原始输入与归一化判定依据。 */
class CorrelationIdsTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdsTest.class);

    private void assertJudgment(String raw, boolean expectedValid) {
        String reason = CorrelationIds.explain(raw);
        boolean actual = CorrelationIds.isValid(raw);
        log.info("rawInput={} -> valid={} (expected={}), reason={}", quote(raw), actual, expectedValid, reason);
        assertEquals(expectedValid, actual, reason);
        assertEquals(expectedValid, reason.startsWith("VALID"), reason);
    }

    private static String quote(String raw) {
        return raw == null ? "null" : "'" + raw + "'";
    }

    @Test
    void validIdsAreAccepted() {
        assertJudgment("valid-id-12345", true);
        assertJudgment("ABCDEFGH", true);
        assertJudgment("a_b-c_d-e_f", true);
        assertJudgment("x".repeat(64), true);
    }

    @Test
    void missingOrBlankIsInvalid() {
        assertJudgment(null, false);
        assertJudgment("", false);
        assertJudgment("   ", false);
    }

    @Test
    void malformedIdsAreInvalid() {
        assertJudgment("short", false);                       // 长度不足
        assertJudgment("x".repeat(65), false);                // 超长
        assertJudgment("bad id with spaces!!", false);        // 非法字符
        assertJudgment("中文标识abcdefghij", false);            // 非 ASCII
        assertJudgment("id/with/slashes-123", false);         // 斜杠
    }

    @Test
    void generatedIdsAreAlwaysValid() {
        for (int i = 0; i < 100; i++) {
            String generated = CorrelationIds.generate();
            log.info("generated={} -> {}", generated, CorrelationIds.explain(generated));
            assertTrue(CorrelationIds.isValid(generated));
        }
    }

    @Test
    void generatedIdsAreUnique() {
        String a = CorrelationIds.generate();
        String b = CorrelationIds.generate();
        log.info("uniqueness check: a={} b={}", a, b);
        assertFalse(a.equals(b));
    }
}
