package com.getjobs.worker.service;

import com.getjobs.worker.dto.JobProgressMessage;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;

class BossProgressTrackerTest {
    @Test void keepsTaskTotalsWhenMovingToAnotherSearchAndRetainsTerminalSnapshot() {
        var tracker = new BossProgressTracker();
        String id = tracker.begin();
        tracker.running(id);
        tracker.search(id, "上海", "Java");
        tracker.job(id, "开发工程师", "示例公司", 10);
        tracker.processed(id, true, 1, 10);
        tracker.processed(id, false, 2, 10);
        tracker.record(JobProgressMessage.info("boss", "第一组完成"));
        var first = tracker.snapshot();
        assertEquals(2, first.current());
        assertEquals(1, first.deliveredCount());
        tracker.search(id, "北京", "Python");
        var next = tracker.snapshot();
        assertEquals(0, next.current());
        assertEquals(0, next.total());
        assertEquals(2, next.processedCount());
        assertNull(next.jobName());
        assertTrue(next.revision() > first.revision());
        tracker.finish(id, "completed", 1);
        tracker.record(JobProgressMessage.success("boss", "任务完成"));
        var done = tracker.snapshot();
        assertFalse(done.isRunning());
        assertEquals("completed", done.state());
        assertNotNull(done.finishedAt());
        assertEquals("任务完成", done.message());
        assertEquals(done.logs(), tracker.snapshot().logs());
        assertEquals(1, done.skippedCount());
    }

    @Test void stoppingRemainsActiveUntilExecutionReturnsAndOldTaskCannotChangeNextTask() {
        var tracker = new BossProgressTracker();
        String first = tracker.begin();
        tracker.running(first);
        assertTrue(tracker.requestStop());
        assertTrue(tracker.snapshot().isRunning());
        assertEquals("stopping", tracker.snapshot().state());
        assertNull(tracker.begin());
        tracker.finish(first, "stopped", 0);
        String second = tracker.begin();
        assertNotEquals(first, second);
        tracker.processed(first, true, 99, 99);
        tracker.finish(first, "completed", 99);
        assertEquals("starting", tracker.snapshot().state());
        assertEquals(0, tracker.snapshot().deliveredCount());
        assertEquals(1, tracker.snapshot().logs().size());
    }

    @Test void boundsLogsAndReturnsImmutableCopies() {
        var tracker = new BossProgressTracker();
        tracker.begin();
        var initial = tracker.snapshot();
        for (int i = 0; i < 250; i++) tracker.record(JobProgressMessage.info("boss", "event-" + i));
        var current = tracker.snapshot();
        assertEquals(200, current.logs().size());
        assertEquals("event-50", current.logs().getFirst().message());
        assertEquals("event-249", current.logs().getLast().message());
        assertEquals(1, initial.logs().size());
        assertThrows(UnsupportedOperationException.class, () -> current.logs().clear());
    }

    @Test void concurrentStartsReserveOnlyOneTask() throws Exception {
        var tracker = new BossProgressTracker();
        var start = new CountDownLatch(1);
        java.util.function.Supplier<String> contender = () -> {
            try { start.await(); } catch (InterruptedException error) { throw new RuntimeException(error); }
            return tracker.begin();
        };
        var first = CompletableFuture.supplyAsync(contender);
        var second = CompletableFuture.supplyAsync(contender);
        start.countDown();
        assertEquals(1, java.util.stream.Stream.of(first.get(), second.get()).filter(java.util.Objects::nonNull).count());
    }
}
