package com.github.h3yow.serviceobservabilityjava.tests;

import com.github.h3yow.serviceobservabilityjava.budget.BudgetDecision;
import com.github.h3yow.serviceobservabilityjava.budget.RequestBudgets;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求级总时间预算头的归一化规则：缺失/非法/越界/合法四类来源确定且可区分。
 * 每条用例都打印原始输入与归一化后的判定依据，日志可直接核对。
 */
class RequestBudgetsTest {

    private static final Logger log = LoggerFactory.getLogger(RequestBudgetsTest.class);

    private static BudgetDecision decideAndLog(String raw) {
        BudgetDecision decision = RequestBudgets.decide(raw);
        log.info("budget decide rawInput={} -> source={} effectiveMillis={} basis={}",
                raw, decision.source(), decision.effectiveMillis(), decision.reason());
        return decision;
    }

    @Test
    void missingHeaderDefaultsWithoutAffectingLegacyCallers() {
        BudgetDecision decision = decideAndLog(null);
        assertEquals(RequestBudgets.SOURCE_DEFAULTED, decision.source());
        assertEquals(RequestBudgets.DEFAULT_BUDGET_MILLIS, decision.effectiveMillis());

        BudgetDecision blank = decideAndLog("   ");
        assertEquals(RequestBudgets.SOURCE_DEFAULTED, blank.source());
        assertEquals(RequestBudgets.DEFAULT_BUDGET_MILLIS, blank.effectiveMillis());
    }

    @Test
    void nonNumericHeaderIsInvalidAndFallsBackToDefault() {
        BudgetDecision decision = decideAndLog("abc");
        assertEquals(RequestBudgets.SOURCE_INVALID, decision.source());
        assertEquals(RequestBudgets.DEFAULT_BUDGET_MILLIS, decision.effectiveMillis());
        // 与缺省同值但来源可区分。
        assertTrue(decision.reason().contains("not a number"));
    }

    @Test
    void zeroAndNegativeAreInvalidAndFallBackToDefault() {
        BudgetDecision zero = decideAndLog("0");
        assertEquals(RequestBudgets.SOURCE_INVALID, zero.source());
        assertEquals(RequestBudgets.DEFAULT_BUDGET_MILLIS, zero.effectiveMillis());

        BudgetDecision negative = decideAndLog("-50");
        assertEquals(RequestBudgets.SOURCE_INVALID, negative.source());
        assertEquals(RequestBudgets.DEFAULT_BUDGET_MILLIS, negative.effectiveMillis());
    }

    @Test
    void aboveMaxIsClampedToMax() {
        BudgetDecision decision = decideAndLog("999999999");
        assertEquals(RequestBudgets.SOURCE_CLAMPED, decision.source());
        assertEquals(RequestBudgets.MAX_BUDGET_MILLIS, decision.effectiveMillis());

        BudgetDecision justAbove = decideAndLog(
                String.valueOf(RequestBudgets.MAX_BUDGET_MILLIS + 1));
        assertEquals(RequestBudgets.SOURCE_CLAMPED, justAbove.source());
        assertEquals(RequestBudgets.MAX_BUDGET_MILLIS, justAbove.effectiveMillis());
    }

    @Test
    void inRangeValueIsProvidedAndAppliedVerbatim() {
        BudgetDecision decision = decideAndLog("250");
        assertEquals(RequestBudgets.SOURCE_PROVIDED, decision.source());
        assertEquals(250L, decision.effectiveMillis());

        BudgetDecision min = decideAndLog(String.valueOf(RequestBudgets.MIN_BUDGET_MILLIS));
        assertEquals(RequestBudgets.SOURCE_PROVIDED, min.source());
        assertEquals(RequestBudgets.MIN_BUDGET_MILLIS, min.effectiveMillis());

        BudgetDecision max = decideAndLog(String.valueOf(RequestBudgets.MAX_BUDGET_MILLIS));
        assertEquals(RequestBudgets.SOURCE_PROVIDED, max.source());
        assertEquals(RequestBudgets.MAX_BUDGET_MILLIS, max.effectiveMillis());
    }

    @Test
    void decisionIsDeterministicForSameInput() {
        BudgetDecision first = decideAndLog("xyz");
        BudgetDecision second = decideAndLog("xyz");
        assertEquals(first.source(), second.source());
        assertEquals(first.effectiveMillis(), second.effectiveMillis());
        assertEquals(first.reason(), second.reason());
    }
}
