package com.getjobs.application.service;

import com.getjobs.worker.boss.BossFollowUpPolicy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 保存跟进进度、会话校验记录和时间，聊天正文始终留在会话页。 */
@Service
@RequiredArgsConstructor
public class BossFollowUpStore {
    private final JdbcTemplate jdbc;
    public static final int HISTORY_PAGE_SIZE = 16;
    private static final String RUN_COLUMNS = """
            id, account_id, trigger_kind, scheduled_at, created_at, started_at, finished_at, state,
            dates, total, checked, confirmed, unconfirmed, failed, message
            """;
    public record Job(String id, String title, String url, String recruiterId, String company, String recruiterTitle, long contactedAt) {}
    public record Run(long id, long accountId, String trigger, Long scheduledAt, long createdAt, Long startedAt, Long finishedAt,
                      String state, List<String> dates, int total, int checked, int confirmed, int unconfirmed, int failed, String message) {}
    public record Log(long id, long timestamp, String type, String message) {}

    @PostConstruct
    public void migrate() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS boss_follow_up_run (
                    account_id INTEGER PRIMARY KEY, started_at INTEGER NOT NULL, finished_at INTEGER,
                    state TEXT NOT NULL, checked INTEGER NOT NULL DEFAULT 0, confirmed INTEGER NOT NULL DEFAULT 0,
                    unconfirmed INTEGER NOT NULL DEFAULT 0, message TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS boss_follow_up_job (
                    account_id INTEGER NOT NULL, job_id TEXT NOT NULL, last_checked_at INTEGER NOT NULL,
                    state TEXT NOT NULL, conversation_key TEXT, observed_conversation_key TEXT,
                    PRIMARY KEY (account_id, job_id), UNIQUE (account_id, conversation_key)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS boss_follow_up_conversation (
                    account_id INTEGER NOT NULL, conversation_key TEXT NOT NULL, attempted_at INTEGER,
                    receipt_key TEXT, receipt_at INTEGER, last_observed_sent_at INTEGER,
                    PRIMARY KEY (account_id, conversation_key)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS boss_follow_up_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL, trigger_kind TEXT NOT NULL,
                    scheduled_at INTEGER, created_at INTEGER NOT NULL, started_at INTEGER, finished_at INTEGER,
                    state TEXT NOT NULL, dates TEXT NOT NULL DEFAULT '', total INTEGER NOT NULL DEFAULT 0,
                    checked INTEGER NOT NULL DEFAULT 0, confirmed INTEGER NOT NULL DEFAULT 0,
                    unconfirmed INTEGER NOT NULL DEFAULT 0, failed INTEGER NOT NULL DEFAULT 0, message TEXT NOT NULL,
                    UNIQUE(account_id, scheduled_at)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS boss_follow_up_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, run_id INTEGER NOT NULL, timestamp INTEGER NOT NULL,
                    type TEXT NOT NULL, message TEXT NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_boss_follow_up_history_account ON boss_follow_up_history(account_id, id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_boss_follow_up_history_pending ON boss_follow_up_history(state, scheduled_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_boss_follow_up_log_run ON boss_follow_up_log(run_id, id)");
        ensureColumn("boss_follow_up_job", "observed_conversation_key", "TEXT");
        ensureColumn("boss_follow_up_conversation", "last_observed_sent_at", "INTEGER");
        // 旧版的点击记录继续用于防止短时间内重发，过了间隔后重新核对原会话。
        jdbc.update("""
                INSERT INTO boss_follow_up_conversation(account_id, conversation_key, attempted_at)
                SELECT account_id, conversation_key, last_checked_at FROM boss_follow_up_job
                WHERE conversation_key IS NOT NULL AND state IN ('attempted', 'confirmed')
                ON CONFLICT(account_id, conversation_key) DO NOTHING
                """);
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE boss_follow_up_run SET state='interrupted', finished_at=?, message='上次跟进因服务关闭而中断'
                WHERE state='running'
                """, now);
        // 旧表保留。把原来仅保存的最近一次跟进纳入新历史，重复启动不会重复导入。
        jdbc.update("""
                INSERT INTO boss_follow_up_history(account_id, trigger_kind, created_at, started_at, finished_at,
                    state, total, checked, confirmed, unconfirmed, message)
                SELECT r.account_id, 'legacy', r.started_at, r.started_at, r.finished_at, r.state,
                    r.checked, r.checked, r.confirmed, r.unconfirmed, r.message
                FROM boss_follow_up_run r
                WHERE NOT EXISTS (SELECT 1 FROM boss_follow_up_history h
                    WHERE h.account_id=r.account_id AND h.trigger_kind='legacy' AND h.created_at=r.started_at)
                """);
        String interrupted = "上次跟进因服务关闭而中断";
        jdbc.update("""
                INSERT INTO boss_follow_up_log(run_id, timestamp, type, message)
                SELECT h.id, ?, 'warning', ? FROM boss_follow_up_history h
                WHERE h.state IN ('queued', 'running') AND NOT EXISTS
                    (SELECT 1 FROM boss_follow_up_log l WHERE l.run_id=h.id AND l.message=?)
                """, now, interrupted, interrupted);
        jdbc.update("""
                UPDATE boss_follow_up_history SET state='interrupted', finished_at=?, message=?
                WHERE state IN ('queued', 'running')
                """, now, interrupted);
    }

    private void ensureColumn(String table, String column, String type) {
        var columns = jdbc.query("PRAGMA table_info(" + table + ")", (row, index) -> row.getString("name"));
        if (!columns.contains(column)) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    public List<Job> candidates(BossFollowUpPolicy.Window window, long now) {
        if (window.dates().isEmpty()) return List.of();
        var args = new ArrayList<>();
        args.add(BossAccountContext.id());
        args.add(now - BossFollowUpPolicy.MESSAGE_INTERVAL_MILLIS);
        args.add(window.cutoff().toLocalDateTime().toString());
        String dateParameters = String.join(", ", Collections.nCopies(window.dates().size(), "?"));
        args.addAll(window.dateLabels());
        return jdbc.query("""
                WITH eligible AS (
                    SELECT d.encrypt_id, d.job_name, d.job_url, d.encrypt_user_id, d.company_name, d.hr_position, f.last_checked_at,
                           COALESCE(datetime(d.delivered_at), datetime(d.processed_at)) AS contacted_at, d.id
                    FROM boss_data d
                    LEFT JOIN boss_follow_up_job f ON f.account_id=d.account_id AND f.job_id=d.encrypt_id
                    LEFT JOIN boss_follow_up_conversation c ON c.account_id=d.account_id
                        AND c.conversation_key=COALESCE(f.observed_conversation_key, f.conversation_key)
                    WHERE d.account_id=? AND d.delivery_status='已投递'
                        AND trim(d.job_url)<>'' AND trim(d.encrypt_id)<>''
                        AND (f.state IS NULL OR f.state<>'answered')
                        AND MAX(COALESCE(c.last_observed_sent_at, 0), COALESCE(c.receipt_at, 0), COALESCE(c.attempted_at, 0)) < ?
                ), candidates AS (
                    SELECT *, ROW_NUMBER() OVER (PARTITION BY encrypt_id ORDER BY contacted_at DESC, id DESC) AS version
                    FROM eligible WHERE contacted_at <= datetime(?) AND date(contacted_at) IN (%s)
                )
                SELECT encrypt_id, job_name, job_url, encrypt_user_id, company_name, hr_position, contacted_at FROM candidates
                WHERE version=1
                ORDER BY last_checked_at IS NOT NULL, last_checked_at, contacted_at DESC, encrypt_id
                """.formatted(dateParameters), (r, n) -> new Job(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
                LocalDateTime.parse(r.getString(7).replace(' ', 'T')).atZone(window.cutoff().getZone()).toInstant().toEpochMilli()),
                args.toArray());
    }

    public void checked(Job job, boolean answered) {
        saveChecked(job, answered ? "answered" : "checked", null);
    }

    @Transactional
    public void rememberLastSent(Job job, String conversationKey, long sentAt) {
        String key = BossReviewStore.hash(conversationKey);
        jdbc.update("""
                INSERT INTO boss_follow_up_conversation(account_id, conversation_key, last_observed_sent_at) VALUES (?, ?, ?)
                ON CONFLICT(account_id, conversation_key) DO UPDATE SET last_observed_sent_at=
                    MAX(COALESCE(boss_follow_up_conversation.last_observed_sent_at, 0), excluded.last_observed_sent_at)
                """, BossAccountContext.id(), key, sentAt);
        saveChecked(job, "checked", key);
    }

    private void saveChecked(Job job, String state, String observedKey) {
        jdbc.update("""
                INSERT INTO boss_follow_up_job(account_id, job_id, last_checked_at, state, observed_conversation_key) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(account_id, job_id) DO UPDATE SET last_checked_at=excluded.last_checked_at, state=excluded.state,
                    observed_conversation_key=COALESCE(excluded.observed_conversation_key, boss_follow_up_job.observed_conversation_key)
                WHERE boss_follow_up_job.state<>'answered'
                """, BossAccountContext.id(), job.id(), System.currentTimeMillis(), state, observedKey);
    }

    /** 点击前记录会话级占用，发送结果不明时也避免立刻重试。 */
    public boolean claim(String conversationKey, long at) {
        return jdbc.update("""
                INSERT INTO boss_follow_up_conversation(account_id, conversation_key, attempted_at) VALUES (?, ?, ?)
                ON CONFLICT(account_id, conversation_key) DO UPDATE SET attempted_at=excluded.attempted_at
                WHERE boss_follow_up_conversation.attempted_at IS NULL OR boss_follow_up_conversation.attempted_at < ?
                """, BossAccountContext.id(), BossReviewStore.hash(conversationKey), at,
                at - BossFollowUpPolicy.MESSAGE_INTERVAL_MILLIS) == 1;
    }

    public void cancelClaim(String conversationKey, long at) {
        jdbc.update("""
                UPDATE boss_follow_up_conversation SET attempted_at=NULL
                WHERE account_id=? AND conversation_key=? AND attempted_at=?
                """, BossAccountContext.id(), BossReviewStore.hash(conversationKey), at);
    }

    public void confirm(String conversationKey, long attemptAt, String messageKey, long sentAt) {
        jdbc.update("""
                UPDATE boss_follow_up_conversation SET receipt_key=?, receipt_at=?
                WHERE account_id=? AND conversation_key=? AND attempted_at=?
                """, BossReviewStore.hash(messageKey), sentAt, BossAccountContext.id(), BossReviewStore.hash(conversationKey), attemptAt);
    }

    /** 只在页面上的最后一条消息能与已确认的回执对应时，补用本地发送时间。 */
    public long confirmedTime(String conversationKey, String messageKey) {
        return jdbc.query("""
                SELECT receipt_at FROM boss_follow_up_conversation WHERE account_id=? AND conversation_key=? AND receipt_key=?
                """, (row, n) -> row.getLong(1), BossAccountContext.id(), BossReviewStore.hash(conversationKey),
                BossReviewStore.hash(messageKey)).stream().findFirst().orElse(0L);
    }

    public List<Run> history(long accountId, Long before) {
        return jdbc.query("SELECT " + RUN_COLUMNS + """
                FROM boss_follow_up_history WHERE account_id=? AND id<?
                ORDER BY id DESC LIMIT ?
                """, BossFollowUpStore::readRun, accountId, before == null ? Long.MAX_VALUE : before, HISTORY_PAGE_SIZE);
    }

    public List<Run> pending() {
        return jdbc.query("SELECT " + RUN_COLUMNS + """
                FROM boss_follow_up_history WHERE state='queued' ORDER BY scheduled_at, id
                """, BossFollowUpStore::readRun);
    }

    private static Run readRun(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        String dates = row.getString("dates");
        return new Run(row.getLong("id"), row.getLong("account_id"), row.getString("trigger_kind"),
                nullableLong(row, "scheduled_at"), row.getLong("created_at"),
                nullableLong(row, "started_at"), nullableLong(row, "finished_at"),
                row.getString("state"), dates.isBlank() ? List.of() : Arrays.asList(dates.split(",")),
                row.getInt("total"), row.getInt("checked"), row.getInt("confirmed"), row.getInt("unconfirmed"),
                row.getInt("failed"), row.getString("message"));
    }

    private static Long nullableLong(java.sql.ResultSet row, String field) throws java.sql.SQLException {
        long value = row.getLong(field);
        return row.wasNull() ? null : value;
    }

    public List<Log> logs(long accountId, long runId) {
        return jdbc.query("""
                SELECT l.id, l.timestamp, l.type, l.message
                FROM boss_follow_up_log l JOIN boss_follow_up_history h ON h.id=l.run_id
                WHERE h.account_id=? AND h.id=? ORDER BY l.id
                """, (row, n) -> new Log(row.getLong(1), row.getLong(2), row.getString(3), row.getString(4)), accountId, runId);
    }

    @Transactional
    public void enqueue(long accountId, long scheduledAt, List<String> dates, long now) {
        String message = "已到设定时间，等待空闲浏览器";
        var inserted = jdbc.query("""
                INSERT INTO boss_follow_up_history(account_id, trigger_kind, scheduled_at, created_at, dates, state, message)
                VALUES (?, 'scheduled', ?, ?, ?, 'queued', ?)
                ON CONFLICT(account_id, scheduled_at) DO NOTHING RETURNING id
                """, (row, n) -> row.getLong(1), accountId, scheduledAt, now, String.join(",", dates), message);
        if (!inserted.isEmpty()) append(inserted.getFirst(), now, "info", message);
    }

    @Transactional
    public long startManual(long accountId, List<String> dates, long now) {
        String message = "已手动启动跟进";
        long runId = jdbc.queryForObject("""
                INSERT INTO boss_follow_up_history(account_id, trigger_kind, created_at, started_at, dates, state, message)
                VALUES (?, 'manual', ?, ?, ?, 'running', ?) RETURNING id
                """, Long.class, accountId, now, now, String.join(",", dates), message);
        append(runId, now, "info", message);
        return runId;
    }

    @Transactional
    public boolean startScheduled(long runId, long now) {
        String message = "已启动定时跟进";
        if (jdbc.update("""
                UPDATE boss_follow_up_history SET started_at=?, state='running', message=?
                WHERE id=? AND state='queued'
                """, now, message, runId) != 1) return false;
        append(runId, now, "info", message);
        return true;
    }

    @Transactional
    public void progress(long runId, int total, int checked, int confirmed, int unconfirmed, int failed,
                         String type, String message) {
        if (jdbc.update("""
                UPDATE boss_follow_up_history SET total=?, checked=?, confirmed=?, unconfirmed=?, failed=?, message=?
                WHERE id=? AND state='running'
                """, total, checked, confirmed, unconfirmed, failed, message, runId) == 1) {
            append(runId, System.currentTimeMillis(), type, message);
        }
    }

    @Transactional
    public void finish(long runId, String state, int checked, int confirmed, int unconfirmed, int failed, String message) {
        long now = System.currentTimeMillis();
        if (jdbc.update("""
                UPDATE boss_follow_up_history SET finished_at=?, state=?, checked=?, confirmed=?, unconfirmed=?, failed=?, message=?
                WHERE id=? AND state IN ('queued', 'running')
                """, now, state, checked, confirmed, unconfirmed, failed, message, runId) == 1) {
            String type = switch (state) {
                case "failed" -> "error";
                case "completed" -> "success";
                default -> "warning";
            };
            append(runId, now, type, message);
        }
    }

    private void append(long runId, long at, String type, String message) {
        jdbc.update("INSERT INTO boss_follow_up_log(run_id, timestamp, type, message) VALUES (?, ?, ?, ?)",
                runId, at, type, message);
    }
}
