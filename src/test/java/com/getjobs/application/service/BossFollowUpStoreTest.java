package com.getjobs.application.service;

import com.getjobs.worker.boss.BossFollowUpPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BossFollowUpStoreTest {
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private BossFollowUpStore store;
    private final BossFollowUpPolicy policy = new BossFollowUpPolicy(false, null,
            new BossFollowUpPolicy.DateSelection(List.of(0, 1, 2, 3, 4, 5, 6), List.of()));

    @BeforeEach void setup() {
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("follow-up.db"));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE boss_data(id INTEGER PRIMARY KEY, account_id INTEGER, encrypt_id TEXT, job_name TEXT,
                    job_url TEXT, encrypt_user_id TEXT, company_name TEXT, hr_position TEXT, delivered_at TEXT, processed_at TEXT,
                    delivery_status TEXT)
                """);
        store = new BossFollowUpStore(jdbc);
        store.migrate();
    }

    @Test void selectsOnlyDueDeliveredJobsAndMovesAlreadyCheckedJobsBehindNewOnes() {
        add(1, "older", 80, "已投递");
        add(1, "last", 50, "已投递");
        add(1, "last", 50, "已投递");
        add(1, "future", -4, "已投递");
        add(1, "filtered", 72, "已过滤");
        add(2, "another", 72, "已投递");
        long now = System.currentTimeMillis();
        var window = policy.window(now);
        try (var scope = BossAccountContext.use(1)) {
            var first = store.candidates(window, now);
            assertEquals(List.of("last", "older"), first.stream().map(BossFollowUpStore.Job::id).toList());
            store.checked(first.getFirst(), false);
            assertEquals("older", store.candidates(window, now).getFirst().id());
            store.checked(first.getLast(), true);
            assertEquals(List.of("last"), store.candidates(window, now).stream().map(BossFollowUpStore.Job::id).toList());
        }
    }

    @Test void includesEveryCandidateAndKeepsConversationCooldownAcrossRuns() {
        long now = System.currentTimeMillis();
        long interval = BossFollowUpPolicy.MESSAGE_INTERVAL_MILLIS;
        for (int i = 1; i <= 25; i++) add(1, "job-" + i, 48L + i, "已投递");

        var first = store.candidates(policy.window(now), now);
        assertEquals(25, first.size());
        for (int i = 0; i < 20; i++) {
            var job = first.get(i);
            var key = "conversation:" + job.id();
            // 覆盖读取到的本人消息、点击后回执不明和平台确认发送三种来源。
            store.rememberLastSent(job, key, i < 10 ? now : now - 2 * interval);
            if (i >= 10 && i < 15) assertTrue(store.claim(key, now));
            if (i >= 15) {
                long attemptAt = now - TimeUnit.SECONDS.toMillis(1);
                assertTrue(store.claim(key, attemptAt));
                store.confirm(key, attemptAt, "receipt-" + job.id(), now);
            }
        }
        long afterThreeMinutes = now + TimeUnit.MINUTES.toMillis(3);
        var second = store.candidates(policy.window(afterThreeMinutes), afterThreeMinutes);
        assertEquals(List.of("job-21", "job-22", "job-23", "job-24", "job-25"), second.stream().map(BossFollowUpStore.Job::id).toList());
        for (var job : second) store.rememberLastSent(job, "conversation:" + job.id(), afterThreeMinutes);
        assertTrue(store.candidates(policy.window(afterThreeMinutes), afterThreeMinutes).isEmpty());

        long boundary = now + interval;
        assertTrue(store.candidates(policy.window(boundary), boundary).isEmpty());
        assertEquals(20, store.candidates(policy.window(boundary + 1), boundary + 1).size());
    }

    @Test void combinesCalendarDaysAtMidnightAndPrefersOriginalDeliveryTime() {
        var dates = new BossFollowUpPolicy.DateSelection(List.of(1, 2), List.of("2026-11-19", "2026-11-09"));
        var calendar = new BossFollowUpPolicy(false, null, dates);
        var now = LocalDateTime.parse("2026-11-20T00:02:00").atZone(ZoneId.systemDefault()).toInstant();
        addAt(1, "today", "2026-11-20T00:00:00", null, "已投递");
        addAt(1, "yesterday", "2026-11-19T23:59:59", null, "已投递");
        addAt(1, "fallback", null, "2026-11-18T00:00:00", "已投递");
        addAt(1, "outside", "2026-11-17T23:59:59", "2026-11-18T00:00:00", "已投递");
        addAt(1, "fixed", "2026-11-09T18:00:00", null, "已投递");
        addAt(1, "filtered", "2026-11-19T07:00:00", null, "已过滤");
        addAt(2, "other", "2026-11-19T09:00:00", null, "已投递");
        var jobs = store.candidates(calendar.window(now.toEpochMilli()), now.toEpochMilli());
        assertEquals(List.of("yesterday", "fallback", "fixed"), jobs.stream().map(BossFollowUpStore.Job::id).toList());
    }

    @Test void readsTheRunningRecordBeforeItHasAFinishTime() {
        long runId = store.startManual(1, List.of("2026-11-19"), System.currentTimeMillis());
        var running = store.history(1, null).getFirst();
        assertEquals("running", running.state());
        assertNull(running.finishedAt());
        store = new BossFollowUpStore(jdbc);
        store.migrate();
        assertEquals("interrupted", store.history(1, null).getFirst().state());
        assertEquals("warning", store.logs(1, runId).getLast().type());
    }

    @Test void keepsTwoClockSlotsAndDetailedHistoryWithoutDuplicateRuns() {
        long now = System.currentTimeMillis();
        var dates = List.of("2026-11-18", "2026-11-19");
        store.enqueue(1, now, dates, now);
        store.enqueue(1, now, dates, now + 5_000);
        store.enqueue(1, now + 18_000_000, dates, now);
        store.enqueue(2, now, dates, now);
        assertEquals(3, store.pending().size());
        assertEquals(2, store.history(1, null).size());
        long runId = store.pending().getFirst().id();
        assertTrue(store.startScheduled(runId, now));
        assertFalse(store.startScheduled(runId, now));
        store.progress(runId, 45, 1, 0, 0, 1, "error", "模拟会话无法打开");
        store.finish(runId, "failed", 1, 0, 0, 1, "本轮提前结束");
        assertEquals(4, store.logs(1, runId).size());
        assertEquals("模拟会话无法打开", store.logs(1, runId).get(2).message());
        assertTrue(store.logs(2, runId).isEmpty());
        assertEquals("failed", store.history(1, store.history(1, null).getFirst().id()).getFirst().state());
        assertEquals(dates, store.history(1, null).getFirst().dates());

        jdbc.update("""
                INSERT INTO boss_follow_up_run(account_id, started_at, state, message) VALUES(1, ?, 'completed', '历史轮次')
                """, now - 18_000_000);
        store.migrate();
        store.migrate();
        assertTrue(store.pending().isEmpty());
        assertEquals(3, store.history(1, null).size());
        assertEquals(1, store.history(1, null).stream().filter(run -> "legacy".equals(run.trigger())).count());
        assertEquals(4, store.logs(1, runId).size());
    }

    @Test void rechecksLegacyFollowUpsAndKeepsAConversationCooldownAcrossRestarts() {
        long now = System.currentTimeMillis();
        long interval = BossFollowUpPolicy.MESSAGE_INTERVAL_MILLIS;
        add(1, "attempted", 80, "已投递");
        add(1, "confirmed", 90, "已投递");
        add(1, "ineligible", 95, "已投递");
        for (String state : List.of("attempted", "confirmed", "ineligible")) {
            jdbc.update("INSERT INTO boss_follow_up_job(account_id, job_id, last_checked_at, state, conversation_key) VALUES (1, ?, ?, ?, ?)",
                    state, now, state, BossReviewStore.hash(state));
        }
        store = new BossFollowUpStore(jdbc);
        store.migrate();
        assertEquals(List.of("ineligible"), store.candidates(policy.window(now), now).stream()
                .map(BossFollowUpStore.Job::id).toList());
        long next = now + interval + 1;
        assertEquals(3, store.candidates(policy.window(next), next).size());
        assertFalse(store.claim("attempted", now + interval));
        assertFalse(store.claim("confirmed", now + interval));
        assertTrue(store.claim("confirmed", now + interval + 1));
        assertFalse(store.claim("confirmed", now + interval + 1));
        try (var scope = BossAccountContext.use(2)) {
            assertTrue(store.claim("confirmed", now));
            store.confirm("confirmed", now, "message-2", now);
            assertEquals(0, store.confirmedTime("confirmed", "message-3"));
            assertEquals(now, store.confirmedTime("confirmed", "message-2"));
            assertTrue(store.claim("confirmed", now + interval + 1));
            store.cancelClaim("confirmed", now + interval + 1);
            assertEquals(now, store.confirmedTime("confirmed", "message-2"));
            assertTrue(store.claim("confirmed", now + interval + 2));
        }
    }

    @Test void addsMissingObservationColumnsToAnExistingDatabase() {
        jdbc.execute("ALTER TABLE boss_follow_up_job DROP COLUMN observed_conversation_key");
        jdbc.execute("ALTER TABLE boss_follow_up_conversation DROP COLUMN last_observed_sent_at");
        store.migrate();
        add(1, "job-a", 72, "已投递");
        add(1, "job-b", 73, "已投递");
        add(2, "other-account", 74, "已投递");
        long now = System.currentTimeMillis();
        var window = policy.window(now);
        for (var job : store.candidates(window, now)) {
            store.rememberLastSent(job, "shared-conversation", now);
        }
        assertTrue(store.candidates(window, now).isEmpty());
        try (var scope = BossAccountContext.use(2)) {
            assertEquals("other-account", store.candidates(window, now).getFirst().id());
        }
    }

    private void add(long account, String id, long hoursAgo, String status) {
        var at = LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()).minusHours(hoursAgo).toString();
        addAt(account, id, at, null, status);
    }

    private void addAt(long account, String id, String delivered, String processed, String status) {
        jdbc.update("INSERT INTO boss_data(account_id, encrypt_id, job_url, delivered_at, processed_at, delivery_status) VALUES (?, ?, ?, ?, ?, ?)",
                account, id, "https://www.zhipin.com/job_detail/" + id + ".html", delivered, processed, status);
    }
}
