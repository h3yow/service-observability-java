package com.github.h3yow.serviceobservabilityjava.tests;

import com.github.h3yow.serviceobservabilityjava.downstream.FaultSpec;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 故障规约解析：非法值确定性回落、数值有界裁剪。
 */
class FaultSpecTest {

    private static final Logger log = LoggerFactory.getLogger(FaultSpecTest.class);

    @Test
    void parseIsDeterministicAndBounded() {
        check(FaultSpec.fromHeaders(null, null, null), "none", 0L, 0);
        check(FaultSpec.fromHeaders("  TIMEOUT ", null, null), "timeout", 0L, 0);
        check(FaultSpec.fromHeaders("garbage", null, null), "none", 0L, 0);
        check(FaultSpec.fromHeaders("slow", "999999", null), "slow", 5000L, 0);
        check(FaultSpec.fromHeaders("slow", "-50", "9"), "slow", 0L, 3);
        check(FaultSpec.fromHeaders("fail", "abc", "2"), "fail", 0L, 2);
    }

    private static void check(FaultSpec spec, String mode, long delay, int fails) {
        log.info("parsed -> {}", spec);
        assertEquals(mode, spec.mode());
        assertEquals(delay, spec.delayMillis());
        assertEquals(fails, spec.failAttempts());
    }
}
