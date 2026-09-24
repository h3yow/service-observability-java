package com.github.h3yow.serviceobservabilityjava.tests;

import com.github.h3yow.serviceobservabilityjava.timeline.DefaultRequestTimeline;
import com.github.h3yow.serviceobservabilityjava.timeline.InMemoryTimelineStore;
import com.github.h3yow.serviceobservabilityjava.timeline.RequestTimeline;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间线存储：有界淘汰、最近优先顺序、查询缺失返回空。
 */
class InMemoryTimelineStoreTest {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTimelineStoreTest.class);

    private RequestTimeline timeline(String cid) {
        return new DefaultRequestTimeline(cid, "GET", "/api/process", System.nanoTime());
    }

    @Test
    void capacityEvictsOldestAndRecentIsNewestFirst() {
        InMemoryTimelineStore store = new InMemoryTimelineStore(3);
        store.save(timeline("c1"));
        store.save(timeline("c2"));
        store.save(timeline("c3"));
        store.save(timeline("c4")); // 淘汰 c1
        assertEquals(3, store.size());
        assertFalse(store.find("c1").isPresent(), "最旧条目应被淘汰");
        assertTrue(store.find("c4").isPresent());
        List<RequestTimeline> recent = store.recent(10);
        assertEquals(List.of("c4", "c3", "c2"), recent.stream().map(RequestTimeline::correlationId).toList());
        log.info("after eviction recent order={}",
                recent.stream().map(RequestTimeline::correlationId).toList());
    }

    @Test
    void reSaveMovesEntryToNewest() {
        InMemoryTimelineStore store = new InMemoryTimelineStore(2);
        store.save(timeline("c1"));
        store.save(timeline("c2"));
        store.save(timeline("c1")); // 重存 c1 -> 变为最新，c2 变最旧
        store.save(timeline("c3")); // 淘汰 c2
        assertTrue(store.find("c1").isPresent());
        assertFalse(store.find("c2").isPresent());
        assertEquals(List.of("c3", "c1"),
                store.recent(10).stream().map(RequestTimeline::correlationId).toList());
    }

    @Test
    void findMissingAndLimitAreSafe() {
        InMemoryTimelineStore store = new InMemoryTimelineStore(2);
        store.save(timeline("c1"));
        assertTrue(store.find("nope").isEmpty());
        assertEquals(1, store.recent(10).size(), "limit 超出实际数量时按实际返回");
    }
}
