package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.worker.boss.review.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;

/** 审核设置、任务和理由独立存储，不改变旧表的字段或旧记录。 */
@Service
@RequiredArgsConstructor
public class BossReviewStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public record Run(String id, String mode, String state, long startedAt, Long finishedAt, String error,
                      BossReviewPolicy policy, int total, int passed, int rejected, int pending, int delivered,
                      List<Row> rows, BossReviewUsage usage) {}
    public record Row(long id, BossReviewResult result, String deliveryState, boolean cached) {}

    @PostConstruct public void migrate() {
        try (Connection c=jdbc.getDataSource().getConnection()) {
            boolean exists;
            try (var s=c.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE name='boss_review_runs'")) { exists=r.next(); }
            String url=c.getMetaData().getURL();
            if (!exists && url.startsWith("jdbc:sqlite:") && !url.contains(":memory:") && !url.substring("jdbc:sqlite:".length()).startsWith("file:")) {
                Path db=Path.of(url.substring("jdbc:sqlite:".length())).toAbsolutePath();
                Path backup=db.resolveSibling(db.getFileName()+".before-boss-precision.bak");
                if (!Files.exists(backup)) try (var statement=c.prepareStatement("VACUUM INTO ?")) { statement.setString(1,backup.toString());statement.execute(); }
            }
            boolean economyExists;
            try (var s=c.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE name='boss_review_economy'")) { economyExists=r.next(); }
            if (!economyExists && url.startsWith("jdbc:sqlite:") && !url.contains(":memory:") && !url.substring(12).startsWith("file:")) {
                Path db=Path.of(url.substring(12)).toAbsolutePath();
                Path backup=db.resolveSibling(db.getFileName()+".before-boss-ai-economy.bak");
                if (!Files.exists(backup)) try (var statement=c.prepareStatement("VACUUM INTO ?")) { statement.setString(1,backup.toString());statement.execute(); }
            }
            c.setAutoCommit(false);
            try (var s=c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS boss_review_policy(account_id INTEGER PRIMARY KEY,policy_json TEXT NOT NULL,updated_at INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS boss_review_runs(id TEXT PRIMARY KEY,account_id INTEGER NOT NULL,mode TEXT NOT NULL,state TEXT NOT NULL,policy_json TEXT NOT NULL,started_at INTEGER NOT NULL,finished_at INTEGER,error TEXT NOT NULL DEFAULT '')");
                s.execute("CREATE INDEX IF NOT EXISTS idx_boss_review_runs_account ON boss_review_runs(account_id,started_at)");
                s.execute("CREATE TABLE IF NOT EXISTS boss_review_results(id INTEGER PRIMARY KEY AUTOINCREMENT,account_id INTEGER NOT NULL,run_id TEXT NOT NULL,job_key TEXT NOT NULL,content_hash TEXT NOT NULL,policy_hash TEXT NOT NULL,decision TEXT NOT NULL,result_json TEXT NOT NULL,delivery_state TEXT NOT NULL DEFAULT 'not_sent',cached INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,UNIQUE(account_id,run_id,job_key))");
                s.execute("CREATE INDEX IF NOT EXISTS idx_boss_review_cache ON boss_review_results(account_id,content_hash,policy_hash,created_at)");
                s.execute("CREATE TABLE IF NOT EXISTS boss_review_job_cache(account_id INTEGER NOT NULL,run_id TEXT NOT NULL,job_key TEXT NOT NULL,job_json TEXT NOT NULL,PRIMARY KEY(account_id,run_id,job_key))");
                s.execute("CREATE TABLE IF NOT EXISTS boss_review_economy(account_id INTEGER PRIMARY KEY,settings_json TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS boss_review_semantic_cache(account_id INTEGER NOT NULL,content_hash TEXT NOT NULL,policy_hash TEXT NOT NULL,result_json TEXT NOT NULL,evaluated_at INTEGER NOT NULL,PRIMARY KEY(account_id,content_hash,policy_hash))");
                s.execute("CREATE TABLE IF NOT EXISTS boss_review_run_cost(run_id TEXT PRIMARY KEY,account_id INTEGER NOT NULL,settings_json TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS boss_review_usage(id INTEGER PRIMARY KEY AUTOINCREMENT,account_id INTEGER NOT NULL,run_id TEXT NOT NULL,input_tokens INTEGER NOT NULL,output_tokens INTEGER NOT NULL,total_tokens INTEGER NOT NULL,reasoning_tokens INTEGER NOT NULL,cached_input_tokens INTEGER NOT NULL,model TEXT NOT NULL,economy_applied INTEGER NOT NULL,failed INTEGER NOT NULL,created_at INTEGER NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_boss_review_usage_run ON boss_review_usage(account_id,run_id)");
                s.execute("UPDATE boss_review_runs SET state='interrupted',finished_at="+System.currentTimeMillis()+",error='程序已重启，本次审核未自动恢复' WHERE state='running'");
                c.commit();
            } catch (Exception error) { c.rollback();throw error; }
        } catch (Exception error) { throw new IllegalStateException("无法初始化 Boss 精准审核数据，未覆盖原投递记录",error); }
    }
    public BossReviewPolicy policy() {
        var rows=jdbc.query("SELECT policy_json FROM boss_review_policy WHERE account_id=?",(r,n)->read(r.getString(1),BossReviewPolicy.class),BossAccountContext.id());
        return rows.isEmpty()?BossReviewPolicy.defaults():rows.getFirst().validated();
    }
    public void savePolicy(BossReviewPolicy policy) {
        jdbc.update("INSERT INTO boss_review_policy(account_id,policy_json,updated_at) VALUES(?,?,?) ON CONFLICT(account_id) DO UPDATE SET policy_json=excluded.policy_json,updated_at=excluded.updated_at",
                BossAccountContext.id(),write(policy.validated()),System.currentTimeMillis());
    }
    public BossReviewEconomy economy() {
        var rows=jdbc.queryForList("SELECT settings_json FROM boss_review_economy WHERE account_id=?",String.class,BossAccountContext.id());
        return rows.isEmpty()?BossReviewEconomy.defaults():read(rows.getFirst(),BossReviewEconomy.class).validated();
    }
    public void saveEconomy(BossReviewEconomy settings) {
        jdbc.update("INSERT INTO boss_review_economy(account_id,settings_json) VALUES(?,?) ON CONFLICT(account_id) DO UPDATE SET settings_json=excluded.settings_json",BossAccountContext.id(),write(settings.validated()));
    }
    public String begin(String mode,BossReviewPolicy policy) {
        return begin(mode,policy,economy());
    }
    @org.springframework.transaction.annotation.Transactional
    public String begin(String mode,BossReviewPolicy policy,BossReviewEconomy settings) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO boss_review_runs(id,account_id,mode,state,policy_json,started_at) VALUES(?,?,?,'running',?,?)",id,BossAccountContext.id(),mode,write(policy),System.currentTimeMillis());
        jdbc.update("INSERT INTO boss_review_run_cost(run_id,account_id,settings_json) VALUES(?,?,?)",id,BossAccountContext.id(),write(settings.validated()));
        return id;
    }
    public void finish(String id,String state,String error) {
        jdbc.update("UPDATE boss_review_runs SET state=?,finished_at=?,error=? WHERE id=? AND account_id=?",state,System.currentTimeMillis(),error==null?"":error,id,BossAccountContext.id());
    }
    public void save(String runId,BossReviewResult result,String policyHash,boolean cached) {
        jdbc.update("INSERT INTO boss_review_results(account_id,run_id,job_key,content_hash,policy_hash,decision,result_json,cached,created_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(account_id,run_id,job_key) DO UPDATE SET decision=excluded.decision,result_json=excluded.result_json,cached=excluded.cached,content_hash=excluded.content_hash,policy_hash=excluded.policy_hash,created_at=excluded.created_at",
                BossAccountContext.id(),runId,hash(result.job().key()),contentHash(result.job()),policyHash,result.decision(),write(result),cached?1:0,System.currentTimeMillis());
        if (!cached && "semantic".equals(result.stage()) && Set.of("pass","reject").contains(result.decision()))
            jdbc.update("INSERT INTO boss_review_semantic_cache(account_id,content_hash,policy_hash,result_json,evaluated_at) VALUES(?,?,?,?,?) ON CONFLICT(account_id,content_hash,policy_hash) DO UPDATE SET result_json=excluded.result_json,evaluated_at=excluded.evaluated_at",
                    BossAccountContext.id(),contentHash(result.job()),policyHash,write(result),System.currentTimeMillis());
    }
    public void delivery(String runId,BossReviewJob job,String state) {
        jdbc.update("UPDATE boss_review_results SET delivery_state=? WHERE account_id=? AND run_id=? AND job_key=?",state,BossAccountContext.id(),runId,hash(job.key()));
    }
    public Optional<BossReviewResult> cachedResult(BossReviewJob job,String policyHash) {
        return cachedResult(job,policyHash,economy().cacheDays());
    }
    public Optional<BossReviewResult> cachedResult(BossReviewJob job,String policyHash,int cacheDays) {
        var rows=jdbc.query("SELECT result_json FROM boss_review_semantic_cache WHERE account_id=? AND content_hash=? AND policy_hash=? AND evaluated_at>?",
                (r,n)->read(r.getString(1),BossReviewResult.class),BossAccountContext.id(),contentHash(job),policyHash,System.currentTimeMillis()-cacheDays*86_400_000L);
        return rows.stream().findFirst().map(r -> new BossReviewResult(job,r.decision(),r.category(),r.stage(),r.reason(),r.evidence()));
    }

    /** 在拥有账号上下文的任务线程调用，包含已停止任务中已经返回的请求。 */
    public void recordUsage(String runId,BossJobReviewer.RequestUsage request) {
        var u=request.tokens();
        jdbc.update("INSERT INTO boss_review_usage(account_id,run_id,input_tokens,output_tokens,total_tokens,reasoning_tokens,cached_input_tokens,model,economy_applied,failed,created_at) SELECT ?,?,?,?,?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM boss_review_runs WHERE id=? AND account_id=?)",
                BossAccountContext.id(),runId,u.inputTokens(),u.outputTokens(),u.totalTokens(),u.reasoningTokens(),u.cachedInputTokens(),request.model(),request.economyApplied()?1:0,request.failed()?1:0,System.currentTimeMillis(),runId,BossAccountContext.id());
    }
    public BossReviewUsage usage(String runId) {
        var settings=jdbc.queryForList("SELECT settings_json FROM boss_review_run_cost WHERE run_id=? AND account_id=?",String.class,runId,BossAccountContext.id());
        var counts=jdbc.queryForMap("SELECT COUNT(*) requests,COALESCE(SUM(failed),0) failed,COALESCE(SUM(input_tokens<0 OR output_tokens<0 OR total_tokens<0),0) unreported,COALESCE(SUM(MAX(input_tokens,0)),0) input,COALESCE(SUM(MAX(output_tokens,0)),0) output,COALESCE(SUM(MAX(total_tokens,0)),0) total,SUM(CASE WHEN reasoning_tokens>=0 THEN reasoning_tokens END) reasoning,SUM(CASE WHEN cached_input_tokens>=0 THEN cached_input_tokens END) cached,COALESCE(SUM(economy_applied),0) economy FROM boss_review_usage WHERE account_id=? AND run_id=?",BossAccountContext.id(),runId);
        int reused=jdbc.queryForObject("SELECT COUNT(*) FROM boss_review_results WHERE account_id=? AND run_id=? AND cached=1",Integer.class,BossAccountContext.id(),runId);
        return new BossReviewUsage(!settings.isEmpty(),number(counts,"requests"),number(counts,"failed"),number(counts,"unreported"),
                ((Number)counts.get("input")).longValue(),((Number)counts.get("output")).longValue(),((Number)counts.get("total")).longValue(),
                counts.get("reasoning")==null?null:((Number)counts.get("reasoning")).longValue(),counts.get("cached")==null?null:((Number)counts.get("cached")).longValue(),
                reused,number(counts,"economy"),settings.isEmpty()?BossReviewEconomy.defaults():read(settings.getFirst(),BossReviewEconomy.class));
    }
    public void cacheJob(String runId,BossReviewJob job) {
        if (!job.description().isBlank()) jdbc.update("INSERT INTO boss_review_job_cache(account_id,run_id,job_key,job_json) VALUES(?,?,?,?) ON CONFLICT(account_id,run_id,job_key) DO UPDATE SET job_json=excluded.job_json",BossAccountContext.id(),runId,hash(job.key()),write(job));
    }
    public Optional<BossReviewJob> cachedJob(String runId,BossReviewJob job) {
        return jdbc.query("SELECT job_json FROM boss_review_job_cache WHERE account_id=? AND run_id=? AND job_key=?",(r,n)->read(r.getString(1),BossReviewJob.class),BossAccountContext.id(),runId,hash(job.key())).stream().findFirst();
    }
    public List<BossReviewJob> history(int limit) {
        return jdbc.query("SELECT encrypt_id,job_name,company_name,location,salary,experience,degree,hr_position,hr_active_status,job_description,job_url FROM boss_data WHERE account_id=? ORDER BY updated_at DESC,id DESC LIMIT ?",(r,n)->
                new BossReviewJob(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),"历史岗位"),BossAccountContext.id(),limit);
    }
    public Optional<Run> latest(String decision) {
        var ids=jdbc.queryForList("SELECT id FROM boss_review_runs WHERE account_id=? ORDER BY started_at DESC LIMIT 1",String.class,BossAccountContext.id());
        return ids.isEmpty()?Optional.empty():run(ids.getFirst(),decision);
    }
    public Optional<Run> run(String id,String decision) {
        var rows=jdbc.query("SELECT * FROM boss_review_runs WHERE id=? AND account_id=?",(r,n)-> {
            long end=r.getLong("finished_at"); Long finished=r.wasNull()?null:end;
            return new Run(r.getString("id"),r.getString("mode"),r.getString("state"),r.getLong("started_at"),finished,r.getString("error"),read(r.getString("policy_json"),BossReviewPolicy.class),0,0,0,0,0,List.of(),null);
        },id,BossAccountContext.id());
        if (rows.isEmpty()) return Optional.empty();
        Run run=rows.getFirst();
        var counts=jdbc.queryForMap("SELECT COUNT(*) total,COALESCE(SUM(decision='pass'),0) passed,COALESCE(SUM(decision='reject'),0) rejected,COALESCE(SUM(decision='review'),0) pending,COALESCE(SUM(delivery_state='sent'),0) delivered FROM boss_review_results WHERE account_id=? AND run_id=?",BossAccountContext.id(),id);
        boolean filter=decision!=null && Set.of("pass","reject","review").contains(decision);
        List<Object> args=new ArrayList<>(List.of(BossAccountContext.id(),id)); if(filter)args.add(decision);
        var results=jdbc.query("SELECT id,result_json,delivery_state,cached FROM boss_review_results WHERE account_id=? AND run_id=?"+(filter?" AND decision=?":"")+" ORDER BY id DESC LIMIT 200",(r,n)->new Row(r.getLong(1),read(r.getString(2),BossReviewResult.class),r.getString(3),r.getBoolean(4)),args.toArray());
        return Optional.of(new Run(run.id(),run.mode(),run.state(),run.startedAt(),run.finishedAt(),run.error(),run.policy(),number(counts,"total"),number(counts,"passed"),number(counts,"rejected"),number(counts,"pending"),number(counts,"delivered"),results,usage(id)));
    }
    private static int number(Map<String,Object> map,String key) { return ((Number)map.get(key)).intValue(); }
    public String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) {throw new IllegalStateException("无法序列化审核资料",e);} }
    private <T> T read(String value,Class<T> type) { try { return json.readValue(value,type); } catch(Exception e){throw new IllegalStateException("审核资料格式异常",e);} }
    public static String hash(String value) { try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }
    public String contentHash(BossReviewJob job) { return hash(write(List.of(normalize(job.title()),normalize(job.description())))); }
    private static String normalize(String text) { return text.strip().replaceAll("\\s+"," "); }
}
