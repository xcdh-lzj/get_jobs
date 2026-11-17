package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.worker.boss.review.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BossReviewStoreTest {
    @TempDir Path directory;
    private BossReviewStore store;
    private JdbcTemplate jdbc;
    @BeforeEach void setup() {
        var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("test.db"));
        jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE old_user_data(id INTEGER PRIMARY KEY,value TEXT)");jdbc.update("INSERT INTO old_user_data VALUES(1,'keep-original')");
        store=new BossReviewStore(jdbc,new ObjectMapper());store.migrate();
    }
    @AfterEach void clear() {BossAccountContext.clear();}
    static BossReviewJob job() {return new BossReviewJob("job-1","Agent开发","公司","广州","20-30K","1-3年","本科","","","负责开发 Agent 后端服务与 RAG 接口。","https://www.zhipin.com/job_detail/job-1.html","Agent");}
    @Test void migrationBacksUpAndRetainsAllOriginalTablesAndPoliciesAreAccountScoped() {
        assertTrue(Files.exists(directory.resolve("test.db.before-boss-precision.bak")));
        assertEquals("keep-original",jdbc.queryForObject("SELECT value FROM old_user_data",String.class));
        var defaults=store.policy(); assertTrue(defaults.enabled());
        try(var scope=BossAccountContext.use(2)) { store.savePolicy(new BossReviewPolicy(false,"账号2的目标",List.of("agent_development"),List.of()));assertFalse(store.policy().enabled()); }
        assertTrue(store.policy().enabled());assertEquals(defaults,store.policy());store.migrate();
        assertEquals("keep-original",jdbc.queryForObject("SELECT value FROM old_user_data",String.class));
    }
    @Test void resultsAndCachesNeverCrossAccountsAndResumeOnlyWithinTheSameTask() {
        String run=store.begin("history",store.policy());
        var result=new BossReviewResult(job(),"pass","agent_development","semantic","研发职责明确",List.of("负责开发 Agent 后端服务"));
        store.save(run,result,"rules-1",false);store.cacheJob(run,job());store.delivery(run,job(),"preview_only");
        assertEquals(1,store.run(run,"all").orElseThrow().passed());assertEquals(0,store.run(run,"all").orElseThrow().delivered());
        assertTrue(store.cachedResult(job(),"rules-1").isPresent());assertTrue(store.cachedResult(job(),"different-rules").isEmpty());
        assertTrue(store.cachedJob(run,job()).isPresent());assertTrue(store.cachedJob("new-run",job()).isEmpty());
        try(var scope=BossAccountContext.use(2)) {
            assertTrue(store.run(run,"all").isEmpty());assertTrue(store.cachedResult(job(),"rules-1").isEmpty());assertTrue(store.cachedJob(run,job()).isEmpty());
        }
        store.finish(run,"completed","");assertEquals("completed",store.latest("all").orElseThrow().state());
    }
    @Test void duplicateRecordsUpdateInsteadOfDoubleCountingAndStaleRunsBecomeInterrupted() {
        String run=store.begin("scan",store.policy());
        var pending=BossReviewResult.pending(job(),"等待人工审核");store.save(run,pending,"r",false);store.save(run,pending,"r",true);
        assertEquals(1,store.run(run,"all").orElseThrow().total());
        store.migrate();assertEquals("interrupted",store.run(run,"all").orElseThrow().state());
    }

    @Test void semanticCacheIgnoresMetadataButChangesWithTitleJdGoalAndNeverRenewsOnReuse() {
        String run=store.begin("scan",store.policy());
        var result=new BossReviewResult(job(),"pass","AI Agent 智能体开发","semantic","Agent服务研发",List.of("负责开发 Agent 后端服务"));
        store.save(run,result,"semantic-model-v3",false);
        var changed=new BossReviewJob("another-id",job().title(),"另一家公司","深圳","30-40K","5-10年","硕士","经理","刚刚活跃",job().description(),job().detailUrl(),"应用开发");
        assertTrue(store.cachedResult(changed,"semantic-model-v3",7).isPresent());
        assertTrue(store.cachedResult(changed,"different-target-or-model",7).isEmpty());
        var changedJd=new BossReviewJob("id",changed.title(),"","","","","","","","负责客户销售，不负责研发。","","");
        assertTrue(store.cachedResult(changedJd,"semantic-model-v3",7).isEmpty());
        long evaluation=System.currentTimeMillis()-2*86_400_000L;
        jdbc.update("UPDATE boss_review_semantic_cache SET evaluated_at=?",evaluation);
        String second=store.begin("scan",store.policy());store.save(second,result,"semantic-model-v3",true);
        assertEquals(evaluation,jdbc.queryForObject("SELECT evaluated_at FROM boss_review_semantic_cache",Long.class));
        assertTrue(store.cachedResult(job(),"semantic-model-v3",1).isEmpty());assertTrue(store.cachedResult(job(),"semantic-model-v3",7).isPresent());
        try(var scope=BossAccountContext.use(2)){assertTrue(store.cachedResult(job(),"semantic-model-v3",7).isEmpty());}
        store.save(run,BossReviewResult.reject(changedJd,"标题排除",changedJd.title()),"basic-only",false);
        assertTrue(store.cachedResult(changedJd,"basic-only",7).isEmpty());
    }

    @Test void usageSettingsAndUnknownFailuresAreScopedAndOldRunsAreUntracked() {
        assertTrue(Files.exists(directory.resolve("test.db.before-boss-ai-economy.bak")));
        store.saveEconomy(new BossReviewEconomy(true,14,5000));String run=store.begin("history",store.policy());
        store.recordUsage(run,new BossJobReviewer.RequestUsage(new AiService.TokenUsage(1000,100,1100,0,800),"deepseek-flash",true,false));
        store.recordUsage(run,new BossJobReviewer.RequestUsage(AiService.TokenUsage.unknown(),"",false,true));
        var usage=store.run(run,"all").orElseThrow().usage();
        assertEquals(2,usage.requests());assertEquals(1,usage.failedRequests());assertEquals(1,usage.unreportedRequests());
        assertEquals(1100,usage.totalTokens());assertEquals(800,usage.cachedInputTokens());assertEquals(5000,usage.settings().taskTokenLimit());
        try(var scope=BossAccountContext.use(2)){
            assertEquals(BossReviewEconomy.defaults(),store.economy());assertEquals(0,store.usage(run).requests());
            store.recordUsage(run,new BossJobReviewer.RequestUsage(new AiService.TokenUsage(10,10,20,0,0),"foreign",true,false));
        }
        assertEquals(2,store.usage(run).requests());
        jdbc.update("DELETE FROM boss_review_run_cost WHERE run_id=?",run);
        assertFalse(store.run(run,"all").orElseThrow().usage().tracked());
        assertThrows(IllegalArgumentException.class,()->new BossReviewEconomy(true,0,0).validated());
        assertThrows(IllegalArgumentException.class,()->new BossReviewEconomy(true,7,-1).validated());
    }
}
