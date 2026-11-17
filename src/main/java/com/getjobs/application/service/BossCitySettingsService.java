package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.worker.boss.BossCityPolicy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.*;

/** 切城规则独立存表，原城市顺序仍保存在 boss_config.city_code 中。 */
@Service
@RequiredArgsConstructor
public class BossCitySettingsService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BossAccountLocks locks;
    @PostConstruct public void migrate() {
        try(var c=jdbc.getDataSource().getConnection();var s=c.createStatement()) {
            boolean exists;try(var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE name='boss_city_policy'")){exists=r.next();}
            String url=c.getMetaData().getURL();
            if(!exists && url.startsWith("jdbc:sqlite:") && !url.contains(":memory:") && !url.substring(12).startsWith("file:")) {
                Path db=Path.of(url.substring(12)).toAbsolutePath(),backup=db.resolveSibling(db.getFileName()+".before-boss-multi-city.bak");
                if(!Files.exists(backup))try(var p=c.prepareStatement("VACUUM INTO ?")){p.setString(1,backup.toString());p.execute();}
            }
            s.execute("CREATE TABLE IF NOT EXISTS boss_city_policy(account_id INTEGER PRIMARY KEY,policy_json TEXT NOT NULL,updated_at INTEGER NOT NULL)");
        }catch(Exception e){throw new IllegalStateException("无法初始化 Boss 多城市设置，未覆盖原配置",e);}
    }
    public BossCityPolicy policy() {
        var rows=jdbc.queryForList("SELECT policy_json FROM boss_city_policy WHERE account_id=?",String.class,BossAccountContext.id());
        if(rows.isEmpty())return BossCityPolicy.defaults();
        try{return json.readValue(rows.getFirst(),BossCityPolicy.class).validated();}catch(Exception e){throw new IllegalStateException("城市切换规则格式异常",e);}
    }
    public void save(BossCityPolicy policy) {
        synchronized(locks) {
            locks.requireEditable(BossAccountContext.id());policy.validated();
            try {jdbc.update("INSERT INTO boss_city_policy(account_id,policy_json,updated_at) VALUES(?,?,?) ON CONFLICT(account_id) DO UPDATE SET policy_json=excluded.policy_json,updated_at=excluded.updated_at",BossAccountContext.id(),json.writeValueAsString(policy),System.currentTimeMillis());}
            catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("无法保存城市规则",e);}
        }
    }
    public void copy(long source,long destination) {
        jdbc.update("INSERT INTO boss_city_policy(account_id,policy_json,updated_at) SELECT ?,policy_json,? FROM boss_city_policy WHERE account_id=? ON CONFLICT(account_id) DO UPDATE SET policy_json=excluded.policy_json,updated_at=excluded.updated_at",destination,System.currentTimeMillis(),source);
    }
}
