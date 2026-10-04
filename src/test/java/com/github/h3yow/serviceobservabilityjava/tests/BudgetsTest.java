package com.github.h3yow.serviceobservabilityjava.tests;

import com.github.h3yow.serviceobservabilityjava.budget.BudgetDecision;
import com.github.h3yow.serviceobservabilityjava.budget.Budgets;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求级总预算归一化规则单测：每个用例打印原始输入与归一化后的判定依据。
 */
class BudgetsTest {

    private static final Logger log = LoggerFactory.getLogger(BudgetsTest.class);

    private BudgetDecision decideAndLog(String raw) {
        BudgetDecision decision = Budgets.decide(raw);
        log.info("rawInput={} -> source={} effectiveMillis={} basis={}",
                raw, decision.source(), decision.effectiveMillis(), decision.reason());
        return decision;
    }

    @Test
    void missingHeaderFallsBackToDefault() {
        BudgetDecision d1 = decideAndLog(null);
        BudgetDecision d2 = decideAndLog("   ");
        assertEquals(Budgets.SOURCE_DEFAULT, d1.source());
        assertEquals(Budgets.SOURCE_DEFAULT, d2.source());
        assertEquals(Budgets.DEFAULT_BUDGET_MILLIS, d1.effectiveMillis());
        assertEquals(Budgets.DEFAULT_BUDGET_MILLIS, d2.effectiveMillis());
    }

    @Test
    void legalValueIsUsedVerbatim() {
        BudgetDecision d = decideAndLog("1500");
        assertEquals(Budgets.SOURCE_PROVIDED, d.source());
        assertEquals(1500L, d.effectiveMillis());
    }

    @Test
    void boundaryValuesAreAccepted() {
        BudgetDecision min = decideAndLog(String.valueOf(Budgets.MIN_BUDGET_MILLIS));
        assertEquals(Budgets.SOURCE_PROVIDED, min.source());
        assertEquals(Budgets.MIN_BUDGET_MILLIS, min.effectiveMillis());
        BudgetDecision max = decideAndLog(String.valueOf(Budgets.MAX_BUDGET_MILLIS));
        assertEquals(Budgets.SOURCE_PROVIDED, max.source());
        assertEquals(Budgets.MAX_BUDGET_MILLIS, max.effectiveMillis());
    }

    @Test
    void nonNumericFallsBackToDefaultWithInvalidSource() {
        BudgetDecision d = decideAndLog("abc");
        assertEquals(Budgets.SOURCE_INVALID, d.source());
        assertEquals(Budgets.DEFAULT_BUDGET_MILLIS, d.effectiveMillis());
    }

    @Test
    void nonPositiveFallsBackToDefaultWithInvalidSource() {
        BudgetDecision zero = decideAndLog("0");
        assertEquals(Budgets.SOURCE_INVALID, zero.source());
        assertEquals(Budgets.DEFAULT_BUDGET_MILLIS, zero.effectiveMillis());
        BudgetDecision negative = decideAndLog("-50");
        assertEquals(Budgets.SOURCE_INVALID, negative.source());
        assertEquals(Budgets.DEFAULT_BUDGET_MILLIS, negative.effectiveMillis());
    }

    @Test
    void belowMinIsClampedToMin() {
        BudgetDecision d = decideAndLog("1");
        assertEquals(Budgets.SOURCE_CLAMPED, d.source());
        assertEquals(Budgets.MIN_BUDGET_MILLIS, d.effectiveMillis());
    }

    @Test
    void aboveMaxIsClampedToMax() {
        BudgetDecision d = decideAndLog("999999");
        assertEquals(Budgets.SOURCE_CLAMPED, d.source());
        assertEquals(Budgets.MAX_BUDGET_MILLIS, d.effectiveMillis());
    }

    @Test
    void decisionIsDeterministic() {
        BudgetDecision a = decideAndLog("abc");
        BudgetDecision b = decideAndLog("abc");
        assertEquals(a.source(), b.source());
        assertEquals(a.effectiveMillis(), b.effectiveMillis());
        assertEquals(a.reason(), b.reason());
    }

    @Test
    void sourcesAreDistinguishable() {
        // 四类来源两两不同，调用方/排障时可明确区分兜底口径。
        var sources = java.util.Set.of(
                decideAndLog(null).source(),
                decideAndLog("abc").source(),
                decideAndLog("1").source(),
                decideAndLog("500").source());
        assertEquals(4, sources.size(), "default/invalid/clamped/provided 必须两两可区分: " + sources);
        assertTrue(sources.contains(Budgets.SOURCE_DEFAULT));
        assertTrue(sources.contains(Budgets.SOURCE_INVALID));
        assertTrue(sources.contains(Budgets.SOURCE_CLAMPED));
        assertTrue(sources.contains(Budgets.SOURCE_PROVIDED));
    }
}
