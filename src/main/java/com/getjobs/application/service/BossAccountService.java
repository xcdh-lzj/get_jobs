package com.getjobs.application.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

@Service
@RequiredArgsConstructor
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 1)
public class BossAccountService implements org.springframework.boot.CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final BossService bossService;
    private final BossAccountLocks locks;
    private final BossCitySettingsService citySettings;
    private final BossHrSettingsService hrSettings;

    public record Account(long id, String name, boolean defaultAccount) {}
    public record Profile(String introduce, String prompt) {}

    /** 增量迁移；旧数据都归属默认账号，旧浏览器目录和 Cookie 表保持兼容。 */
    @PostConstruct
    public void migrate() {
        try (Connection c = jdbc.getDataSource().getConnection(); Statement s = c.createStatement()) {
            boolean exists;
            try (var r = s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='boss_accounts'")) {
                exists = r.next();
            }
            boolean globalBlacklistIndex=false;
            try(var indexes=s.executeQuery("PRAGMA index_list('boss_blacklist')")) {
                while(indexes.next())if("idx_blacklist_type_value".equals(indexes.getString("name")) && indexes.getInt("unique")==1)globalBlacklistIndex=true;
            }
            var blacklistColumns=new java.util.ArrayList<String>();
            try(var columns=s.executeQuery("PRAGMA index_info('idx_blacklist_type_value')")){while(columns.next())blacklistColumns.add(columns.getString("name"));}
            globalBlacklistIndex=globalBlacklistIndex && blacklistColumns.equals(List.of("type","value"));
            if(globalBlacklistIndex && c.getMetaData().getURL().startsWith("jdbc:sqlite:") && !c.getMetaData().getURL().contains(":memory:")) {
                String url=c.getMetaData().getURL().substring(12);
                if(!url.startsWith("file:")) {
                    Path db=Path.of(url).toAbsolutePath(),backup=db.resolveSibling(db.getFileName()+".before-boss-blacklist-copy.bak");
                    if(!Files.exists(backup))try(var p=c.prepareStatement("VACUUM INTO ?")){p.setString(1,backup.toString());p.execute();}
                }
            }
            if (!exists && c.getMetaData().getURL().startsWith("jdbc:sqlite:")
                    && !c.getMetaData().getURL().contains(":memory:")) {
                // VACUUM INTO 是 SQLite 的一致性备份，包含 Cookie；放在原数据库旁，不提交 Git。
                String url = c.getMetaData().getURL().substring("jdbc:sqlite:".length());
                if (!url.startsWith("file:")) {
                    Path db = Path.of(url).toAbsolutePath();
                    Path backup = db.resolveSibling(db.getFileName() + ".before-boss-accounts.bak");
                    if (!Files.exists(backup)) {
                        try (var p = c.prepareStatement("VACUUM INTO ?")) { p.setString(1, backup.toString()); p.execute(); }
                    }
                }
            }
            c.setAutoCommit(false);
            try {
                s.execute("CREATE TABLE IF NOT EXISTS boss_accounts (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, cookie_value TEXT NOT NULL DEFAULT '', introduce TEXT NOT NULL DEFAULT '', prompt TEXT NOT NULL DEFAULT '', profile_seeded INTEGER NOT NULL DEFAULT 0, created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
                s.execute("INSERT OR IGNORE INTO boss_accounts(id,name) VALUES(1,'默认账号')");
                for (String table : List.of("boss_config", "boss_data", "boss_blacklist")) {
                    boolean present = false, scoped = false;
                    try (var columns = s.executeQuery("PRAGMA table_info('" + table + "')")) {
                        while (columns.next()) { present = true; if ("account_id".equals(columns.getString("name"))) scoped = true; }
                    }
                    if (!present) throw new IllegalStateException("缺少数据表 " + table + "，请先安装完整数据库");
                    if (!scoped) s.execute("ALTER TABLE " + table + " ADD COLUMN account_id INTEGER NOT NULL DEFAULT 1");
                    s.execute("CREATE INDEX IF NOT EXISTS idx_" + table + "_account ON " + table + "(account_id)");
                }
                migrateJobHistory(s);
                // 旧库按全局 (type,value) 去重，会阻止两个账号保存相同黑名单。
                // 仅替换该已知索引，保留所有表、条目 ID 和时间戳；与账号迁移一起原子提交。
                if(globalBlacklistIndex) {
                    s.execute("DROP INDEX idx_blacklist_type_value");
                    s.execute("CREATE UNIQUE INDEX idx_blacklist_type_value ON boss_blacklist(account_id,type,value)");
                }
                s.execute("UPDATE boss_accounts SET introduce=COALESCE((SELECT introduce FROM ai ORDER BY id DESC LIMIT 1),''), prompt=COALESCE((SELECT prompt FROM ai ORDER BY id DESC LIMIT 1),''), profile_seeded=1 WHERE id=1 AND profile_seeded=0 AND EXISTS(SELECT 1 FROM ai)");
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (Exception e) { throw new IllegalStateException("Boss 多账号数据库迁移失败，原数据未被覆盖", e); }
    }

    private void migrateJobHistory(Statement statement) throws java.sql.SQLException {
        var columns = new java.util.HashSet<String>();
        try (var rows = statement.executeQuery("PRAGMA table_info('boss_data')")) {
            while (rows.next()) columns.add(rows.getString("name"));
        }
        if (!columns.contains("delivered_at")) {
            statement.execute("ALTER TABLE boss_data ADD COLUMN delivered_at TEXT");
        }
        if (columns.contains("delivery_reason")) return;
        statement.execute("ALTER TABLE boss_data ADD COLUMN delivery_reason TEXT");
        try (var rows = statement.executeQuery("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type='table' AND name IN ('boss_review_results', 'boss_review_runs')
                """)) {
            if (!rows.next() || rows.getInt(1) != 2) return;
        }
        // 只恢复能按账号、岗位和结果状态对应上的历史审核原因。
        statement.executeUpdate("""
                WITH reviews AS (
                    SELECT r.id, r.account_id, r.created_at,
                        CASE WHEN json_valid(r.result_json) THEN r.result_json END AS payload,
                        CASE
                            WHEN r.decision='reject' THEN '已过滤'
                            WHEN r.decision='review' THEN '未投递'
                            WHEN r.decision='pass' AND r.delivery_state='sent' THEN '已投递'
                        END AS status
                    FROM boss_review_results r JOIN boss_review_runs run
                        ON run.id=r.run_id AND run.account_id=r.account_id
                    WHERE run.mode IN ('delivery', 'scan')
                ), reasons AS (
                    SELECT account_id, status,
                        json_extract(payload, '$.job.id') AS job_id,
                        TRIM(json_extract(payload, '$.reason')) AS reason,
                        ROW_NUMBER() OVER (
                            PARTITION BY account_id, status, json_extract(payload, '$.job.id')
                            ORDER BY created_at DESC, id DESC
                        ) AS rank
                    FROM reviews
                    WHERE status IS NOT NULL AND TRIM(json_extract(payload, '$.reason')) <> ''
                )
                UPDATE boss_data
                SET delivery_reason = '历史审核：' || reasons.reason
                FROM reasons
                WHERE boss_data.account_id = reasons.account_id
                    AND boss_data.encrypt_id = reasons.job_id AND reasons.job_id <> ''
                    AND boss_data.delivery_status = reasons.status AND reasons.rank = 1
                """);
    }

    @Override public void run(String... args) {
        // 全新安装的公共 AI 默认值在 CommandLineRunner 中才写入；只继承一次。
        jdbc.update("UPDATE boss_accounts SET introduce=COALESCE((SELECT introduce FROM ai ORDER BY id DESC LIMIT 1),''), prompt=COALESCE((SELECT prompt FROM ai ORDER BY id DESC LIMIT 1),''), profile_seeded=1 WHERE id=1 AND profile_seeded=0 AND EXISTS(SELECT 1 FROM ai)");
    }

    public List<Account> list() {
        return jdbc.query("SELECT id,name FROM boss_accounts ORDER BY id", (r,n) -> new Account(r.getLong(1),r.getString(2),r.getLong(1)==1));
    }
    public Account require(long id) {
        return list().stream().filter(a -> a.id()==id).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Boss 账号不存在"));
    }
    @Transactional
    public Account create(String name, Long copyFrom) {
        name = validName(name);
        if (copyFrom != null) require(copyFrom);
        long id = jdbc.queryForObject("INSERT INTO boss_accounts(name,prompt,profile_seeded) VALUES(?,COALESCE((SELECT prompt FROM boss_accounts WHERE id=1),''),1) RETURNING id", Long.class, name);
        try (var scope = BossAccountContext.use(id)) {
            var config = new com.getjobs.application.entity.BossConfigEntity();
            config.setCityCode("全国"); config.setKeywords(""); config.setSayHi("");
            config.setEnableAi(0); config.setSendImgResume(0); config.setAllowRepeatChat(0);
            if (copyFrom != null) {
                try (var source = BossAccountContext.use(copyFrom)) { config = bossService.getFirstConfig(); }
                if (config == null) config = new com.getjobs.application.entity.BossConfigEntity();
                config.setId(null); config.setSendImgResume(0);
            }
            bossService.saveConfig(config);
            if(copyFrom!=null)citySettings.copy(copyFrom,id);
            if(copyFrom!=null && hrSettings!=null)hrSettings.copy(copyFrom,id);
        }
        return require(id);
    }
    public void rename(long id, String name) {
        synchronized (locks) { locks.requireEditable(id); require(id); jdbc.update("UPDATE boss_accounts SET name=? WHERE id=?",validName(name),id); }
    }
    public Profile profile(long id) {
        require(id);
        return jdbc.queryForObject("SELECT introduce,prompt FROM boss_accounts WHERE id=?",(r,n)->new Profile(r.getString(1),r.getString(2)),id);
    }
    public void saveProfile(long id, String introduce, String prompt) {
        synchronized (locks) {
            locks.requireEditable(id); require(id);
            jdbc.update("UPDATE boss_accounts SET introduce=?,prompt=? WHERE id=?",introduce==null?"":introduce,prompt==null?"":prompt,id);
        }
    }
    public String cookies(long id) {
        require(id);
        return jdbc.queryForObject("SELECT cookie_value FROM boss_accounts WHERE id=?",String.class,id);
    }
    public void saveCookies(long id, String value) { jdbc.update("UPDATE boss_accounts SET cookie_value=? WHERE id=?",value,id); }
    private String validName(String name) {
        if (name==null || name.isBlank() || name.trim().length()>40)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"账号名称须为 1 至 40 个字符");
        return name.trim();
    }
}
