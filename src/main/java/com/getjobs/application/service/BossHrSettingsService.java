package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.worker.boss.BossHrActivityPolicy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.*;

@Service
@RequiredArgsConstructor
public class BossHrSettingsService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BossAccountLocks locks;
    @PostConstruct public void migrate() {
        try(var c=jdbc.getDataSource().getConnection();var s=c.createStatement()) {
            boolean exists;try(var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE name='boss_hr_policy'")){exists=r.next();}
            String url=c.getMetaData().getURL();
            if(!exists && url.startsWith("jdbc:sqlite:") && !url.contains(":memory:") && !url.substring(12).startsWith("file:")) {
                Path db=Path.of(url.substring(12)).toAbsolutePath(),backup=db.resolveSibling(db.getFileName()+".before-boss-hr-activity.bak");
                if(!Files.exists(backup))try(var p=c.prepareStatement("VACUUM INTO ?")){p.setString(1,backup.toString());p.execute();}
            }
            s.execute("CREATE TABLE IF NOT EXISTS boss_hr_policy(account_id INTEGER PRIMARY KEY,policy_json TEXT NOT NULL,updated_at INTEGER NOT NULL)");
        }catch(Exception error){throw new IllegalStateException("无法初始化 Boss HR 活跃筛选设置，原配置未覆盖",error);}
    }
    public BossHrActivityPolicy policy() {
        var rows=jdbc.queryForList("SELECT policy_json FROM boss_hr_policy WHERE account_id=?",String.class,BossAccountContext.id());
        if(rows.isEmpty())return BossHrActivityPolicy.defaults();
        try{return json.readValue(rows.getFirst(),BossHrActivityPolicy.class).validated();}
        catch(Exception error){throw new IllegalStateException("HR 活跃筛选设置格式异常",error);}
    }
    public void save(BossHrActivityPolicy policy) {
        synchronized(locks) {
            locks.requireEditable(BossAccountContext.id());policy.validated();
            try{jdbc.update("INSERT INTO boss_hr_policy(account_id,policy_json,updated_at) VALUES(?,?,?) ON CONFLICT(account_id) DO UPDATE SET policy_json=excluded.policy_json,updated_at=excluded.updated_at",BossAccountContext.id(),json.writeValueAsString(policy),System.currentTimeMillis());}
            catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("无法保存 HR 活跃筛选设置",error);}
        }
    }
    public void copy(long source,long destination){jdbc.update("INSERT INTO boss_hr_policy(account_id,policy_json,updated_at) SELECT ?,policy_json,? FROM boss_hr_policy WHERE account_id=? ON CONFLICT(account_id) DO UPDATE SET policy_json=excluded.policy_json,updated_at=excluded.updated_at",destination,System.currentTimeMillis(),source);}
}
