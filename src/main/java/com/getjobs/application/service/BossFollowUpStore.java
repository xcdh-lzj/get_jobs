package com.getjobs.application.service;

import com.getjobs.worker.boss.BossFollowUpPolicy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 保存跟进进度、会话校验记录和时间，聊天正文始终留在会话页。 */
@Service
@RequiredArgsConstructor
public class BossFollowUpStore {
    private final JdbcTemplate jdbc;
    public record Job(String id, String title, String url, String recruiterId, String company, String recruiterTitle, long contactedAt) {}
    public record Run(long startedAt, Long finishedAt, String state, int checked, int confirmed, int unconfirmed, String message) {}

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
        ensureColumn("boss_follow_up_job", "observed_conversation_key", "TEXT");
        ensureColumn("boss_follow_up_conversation", "last_observed_sent_at", "INTEGER");
        // 旧版的点击记录继续用于防止短时间内重发，过了间隔后重新核对原会话。
        jdbc.update("""
                INSERT INTO boss_follow_up_conversation(account_id, conversation_key, attempted_at)
                SELECT account_id, conversation_key, last_checked_at FROM boss_follow_up_job
                WHERE conversation_key IS NOT NULL AND state IN ('attempted', 'confirmed')
                ON CONFLICT(account_id, conversation_key) DO NOTHING
                """);
        jdbc.update("""
                UPDATE boss_follow_up_run SET state='interrupted', finished_at=?, message='上次跟进因服务关闭而中断'
                WHERE state='running'
                """, System.currentTimeMillis());
    }

    private void ensureColumn(String table, String column, String type) {
        var columns = jdbc.query("PRAGMA table_info(" + table + ")", (row, index) -> row.getString("name"));
        if (!columns.contains(column)) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    public List<Job> candidates(BossFollowUpPolicy.Window window, int batchSize, long now) {
        var args = new ArrayList<>();
        args.add(BossAccountContext.id());
        args.add(now - BossFollowUpPolicy.MESSAGE_INTERVAL_MILLIS);
        args.add(window.cutoff().toLocalDateTime().toString());
        String dateFilter = "";
        if (!window.dates().isEmpty()) {
            dateFilter = "AND date(contacted_at) IN (" + String.join(", ", Collections.nCopies(window.dates().size(), "?")) + ")";
            window.dates().forEach(date -> args.add(date.toString()));
        }
        args.add(batchSize);
        return jdbc.query("""
                WITH candidates AS (
                    SELECT d.encrypt_id, d.job_name, d.job_url, d.encrypt_user_id, d.company_name, d.hr_position, f.last_checked_at,
                           COALESCE(datetime(d.delivered_at), datetime(d.processed_at)) AS contacted_at,
                           ROW_NUMBER() OVER (PARTITION BY d.encrypt_id ORDER BY d.id DESC) AS version
                    FROM boss_data d
                    LEFT JOIN boss_follow_up_job f ON f.account_id=d.account_id AND f.job_id=d.encrypt_id
                    LEFT JOIN boss_follow_up_conversation c ON c.account_id=d.account_id
                        AND c.conversation_key=COALESCE(f.observed_conversation_key, f.conversation_key)
                    WHERE d.account_id=? AND d.delivery_status='已投递'
                        AND trim(d.job_url)<>'' AND trim(d.encrypt_id)<>''
                        AND (f.state IS NULL OR f.state<>'answered')
                        AND MAX(COALESCE(c.last_observed_sent_at, 0), COALESCE(c.receipt_at, 0), COALESCE(c.attempted_at, 0)) < ?
                )
                SELECT encrypt_id, job_name, job_url, encrypt_user_id, company_name, hr_position, contacted_at FROM candidates
                WHERE version=1 AND contacted_at <= datetime(?) %s
                ORDER BY last_checked_at IS NOT NULL, last_checked_at, contacted_at DESC, encrypt_id
                LIMIT ?
                """.formatted(dateFilter), (r, n) -> new Job(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
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

    public Run lastRun(long accountId) {
        return jdbc.query("""
                SELECT started_at, finished_at, state, checked, confirmed, unconfirmed, message
                FROM boss_follow_up_run WHERE account_id=?
                """, (row, n) -> {
            long finished = row.getLong(2);
            Long finishedAt = row.wasNull() ? null : finished;
            return new Run(row.getLong(1), finishedAt, row.getString(3), row.getInt(4), row.getInt(5), row.getInt(6), row.getString(7));
        }, accountId).stream().findFirst().orElse(null);
    }

    public boolean due(long accountId, BossFollowUpPolicy policy, long now) {
        Run last = lastRun(accountId);
        if (last == null) return true;
        long reference = "stopped".equals(last.state()) && last.finishedAt() != null ? last.finishedAt() : last.startedAt();
        return now - reference >= TimeUnit.HOURS.toMillis(policy.intervalHours());
    }

    public void start(long accountId) {
        jdbc.update("""
                INSERT INTO boss_follow_up_run(account_id, started_at, state, message)
                VALUES (?, ?, 'running', '正在核对未回复的会话')
                ON CONFLICT(account_id) DO UPDATE SET started_at=excluded.started_at,
                    state='running', message=excluded.message, finished_at=NULL, checked=0, confirmed=0, unconfirmed=0
                """, accountId, System.currentTimeMillis());
    }

    public void finish(long accountId, String state, int checked, int confirmed, int unconfirmed, String message) {
        jdbc.update("""
                UPDATE boss_follow_up_run SET finished_at=?, state=?, checked=?, confirmed=?, unconfirmed=?, message=?
                WHERE account_id=?
                """, System.currentTimeMillis(), state, checked, confirmed, unconfirmed, message, accountId);
    }
}
