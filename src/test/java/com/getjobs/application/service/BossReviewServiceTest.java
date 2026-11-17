package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.worker.boss.review.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossReviewServiceTest {
    @TempDir Path directory;
    private BossReviewStore store;
    private BossReviewService service;
    private final BossService boss=mock(BossService.class);
    private final AiService ai=mock(AiService.class);
    private final BossAccountLocks locks=new BossAccountLocks();
    @BeforeEach void setup() {
        var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("service.db"));
        store=new BossReviewStore(new JdbcTemplate(source),new ObjectMapper());store.migrate();
        when(boss.getBlackCompanies()).thenReturn(Set.of());when(boss.getBlackJobs()).thenReturn(Set.of());when(boss.getBlackRecruiters()).thenReturn(Set.of());
        when(boss.parseListString(nullable(String.class))).thenCallRealMethod();
        when(boss.toNames(anyString(),anyList())).thenAnswer(call -> call.getArgument(1));
        service=new BossReviewService(store,new BossJobReviewer(ai),boss,locks,null);
    }
    @AfterEach void cleanup() {service.shutdown();BossAccountContext.clear();}

    @Test void explicitCustomSalaryOverridesPresetAndLegacyBoundsAndSurvivesCitySwitch() {
        var config=new BossConfigEntity();config.setSalary("[10-20K]");config.setExpectedSalaryMin(15);config.setExpectedSalaryMax(25);
        config.setSalaryPolicy(new com.getjobs.worker.boss.BossSalaryPolicy("custom",21.5,28.5));when(boss.getFirstConfig()).thenReturn(config);
        var context=service.forCity(service.context(null,false),"广州");
        assertEquals(21.5,context.filters().minMonthlyK());assertEquals(28.5,context.filters().maxMonthlyK());
        assertNull(service.preScreen(BossReviewStoreTest.job().withSalary("22-28K"),context));
        assertEquals("reject",service.preScreen(BossReviewStoreTest.job().withSalary("10-20K"),context).decision());
    }

    @Test void pageSalarySelectionOverridesLegacyHiddenLimitsAndDraftPreviewDoesNotSaveSettings() {
        var config=new BossConfigEntity();config.setCityCode("广州");config.setExpectedSalaryMin(15);config.setExpectedSalaryMax(25);
        config.setSalary("[10-20K]");
        config.setExperience("[不限]");config.setDegree("[不限]");when(boss.getFirstConfig()).thenReturn(config);
        var context=service.context(null,false);
        assertEquals(10.0,context.filters().minMonthlyK());assertEquals(20.0,context.filters().maxMonthlyK());
        assertNull(service.preScreen(BossReviewStoreTest.job(),context));
        var low=BossReviewStoreTest.job().withSalary("9-12K");
        var high=BossReviewStoreTest.job().withSalary("21-25K");
        assertNull(service.preScreen(low,context));assertEquals("reject",service.preScreen(high,context).decision());
        var draft=new BossReviewPolicy(false,"仅试验的目标，不应保存",List.of("agent_development"),List.of());
        var before=store.policy();String id=service.preview("examples",1,draft);awaitFinished(id);
        assertEquals(before,store.policy());assertEquals(draft.goal(),store.run(id,"all").orElseThrow().policy().goal());
        verify(boss,never()).saveOrUpdateFirstSelective(any());
    }
    @Test void citySwitchAndSemanticCacheCannotBypassSelectedSalarySegments() {
        var config=new BossConfigEntity();config.setSalary("[3K以下,50K以上]");config.setExpectedSalaryMin(15);config.setExpectedSalaryMax(25);when(boss.getFirstConfig()).thenReturn(config);
        var context=service.context(null,false);assertTrue(context.filters().hasSalaryFilter());
        assertNull(context.filters().minMonthlyK());assertNull(context.filters().maxMonthlyK());
        var switched=service.forCity(context,"广州");assertEquals(context.filters().salarySelection(),switched.filters().salarySelection());
        var job=BossReviewStoreTest.job().withSalary("10-20K");
        assertEquals("reject",service.preScreen(job,switched).decision());
        assertNull(service.preScreen(job.withSalary("60-70K"),switched));
        config.setSalary("[不限]");var unlimited=service.context(null,false);assertFalse(unlimited.filters().hasSalaryFilter());assertNull(service.preScreen(job.withSalary("面议"),unlimited));
        verify(ai,never()).sendJobReviewRequest(anyString(),anyBoolean(),anyInt());
    }
    @Test void previewRunsInOriginalAccountAndDoesNotExposeResultsOrCachesToAnotherAccount() {
        AtomicInteger requests=new AtomicInteger();
        when(ai.sendJobReviewRequest(anyString(),anyBoolean(),anyInt())).thenAnswer(call -> {requests.incrementAndGet();return AiService.JobReviewResponse.untracked("invalid JSON");});
        String id;try(var scope=BossAccountContext.use(2)) {id=service.preview("examples",2,null);awaitFinished(id);assertEquals(2,service.results(id,"all").orElseThrow().total());}
        assertTrue(service.results(id,"all").isEmpty());assertTrue(service.results(null,"all").isEmpty());assertEquals(1,requests.get());
    }
    @Test void reservedAccountRejectsPolicyEditsAndPreviewBeforeAnyModelCall() {
        locks.reserve(List.of(2L));
        try(var scope=BossAccountContext.use(2)) {
            assertThrows(ResponseStatusException.class,()->service.preview("examples",8,null));
            assertThrows(ResponseStatusException.class,()->service.saveOptions(new BossReviewService.Options(BossReviewPolicy.defaults(),true)));
        }
        verifyNoInteractions(ai);verify(boss,never()).saveOrUpdateFirstSelective(any());
    }
    @Test void concurrentPreviewIsRejectedAndStopPreventsInFlightApprovalBeingRecorded() throws Exception {
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        when(ai.sendJobReviewRequest(anyString(),anyBoolean(),anyInt())).thenAnswer(call -> {entered.countDown();assertTrue(finish.await(5,TimeUnit.SECONDS));return AiService.JobReviewResponse.untracked("invalid JSON");});
        String id=service.preview("examples",1,null);
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(ResponseStatusException.class,()->service.preview("examples",1,null));
            try(var scope=BossAccountContext.use(2)) {assertThrows(ResponseStatusException.class,()->service.stopPreview(id));}
            service.stopPreview(id);
        } finally {finish.countDown();}
        awaitFinished(id);assertEquals("stopped",store.run(id,"all").orElseThrow().state());assertEquals(0,store.run(id,"all").orElseThrow().passed());
        assertEquals(1,store.usage(id).requests()); // 已发出的请求即使停止，也不漏记。
    }

    @Test void cacheReusesSemanticDecisionAcrossMetadataAndCityChangesButAlwaysRechecksBasicRules() {
        when(ai.reviewModelIdentity()).thenReturn("deepseek-flash@official");
        when(ai.sendJobReviewRequest(anyString(),anyBoolean(),anyInt())).thenAnswer(call -> approve(call.getArgument(0)));
        var first=service.context(null,false);String run=service.begin("scan",first);
        service.analyze(run,first,List.of(BossReviewStoreTest.job()),()->false,ignored -> {});
        var changed=new BossReviewJob("different-id",BossReviewStoreTest.job().title(),"另一公司","深圳","25-35K","3-5年","硕士","经理","刚刚活跃",BossReviewStoreTest.job().description(),BossReviewStoreTest.job().detailUrl(),"大模型应用");
        var second=service.forCity(first,"深圳");assertEquals(first.fingerprint(),second.fingerprint());
        String cached=service.begin("scan",second);service.analyze(cached,second,List.of(changed),()->false,ignored -> {});
        assertEquals(1,store.usage(cached).reusedJobs());assertEquals(0,store.usage(cached).requests());
        assertEquals("深圳",store.run(cached,"all").orElseThrow().rows().getFirst().result().job().city());
        when(boss.getBlackJobs()).thenReturn(Set.of("Agent"));
        var excluded=service.context(null,false);String excludedRun=service.begin("scan",excluded);
        service.analyze(excludedRun,excluded,List.of(changed),()->false,ignored -> {});
        assertEquals(1,store.run(excludedRun,"all").orElseThrow().rejected());assertEquals(0,store.usage(excludedRun).requests());
        verify(ai,times(1)).sendJobReviewRequest(anyString(),anyBoolean(),anyInt());
        when(ai.reviewModelIdentity()).thenReturn("different-model");assertNotEquals(first.fingerprint(),service.context(null,false).fingerprint());
        when(ai.reviewModelIdentity()).thenReturn("deepseek-flash@official");
        var policy=new BossReviewPolicy(true,"调整为另一种主要职责",List.of("其他专业"),List.of());
        assertNotEquals(first.fingerprint(),service.context(policy,false).fingerprint());
        store.saveEconomy(new BossReviewEconomy(false,7,0));assertNotEquals(first.fingerprint(),service.context(null,false).fingerprint());
    }

    @Test void budgetSettlesOneRequestThenStopsAndSettingsValidationIsAtomic() {
        store.saveEconomy(new BossReviewEconomy(true,7,1000));
        when(ai.sendJobReviewRequest(anyString(),anyBoolean(),anyInt())).thenAnswer(call -> approve(call.getArgument(0)));
        var context=service.context(null,false);String run=service.begin("history",context);
        List<BossReviewJob> jobs=new ArrayList<>();
        for(int i=0;i<9;i++){var j=BossReviewStoreTest.job();jobs.add(new BossReviewJob("id-"+i,j.title()+i,j.company(),j.city(),j.salary(),j.experience(),j.degree(),"","",j.description(),j.detailUrl().replace("job-1","id-"+i),j.keyword()));}
        assertThrows(BossReviewBudgetReachedException.class,()->service.analyze(run,context,jobs,()->false,ignored -> {}));
        verify(ai,times(1)).sendJobReviewRequest(anyString(),eq(true),eq(1632));
        assertEquals(1100,store.usage(run).totalTokens());assertEquals(1,store.usage(run).requests());assertEquals(4,store.run(run,"all").orElseThrow().passed());
        var policy=store.policy();
        assertThrows(IllegalArgumentException.class,()->service.saveOptions(new BossReviewService.Options(new BossReviewPolicy(false,"changed",List.of("other"),List.of()),false,new BossReviewEconomy(true,0,0))));
        assertEquals(policy,store.policy());
    }

    @Test void budgetPreviewAlsoStopsWhenProviderDoesNotReturnUsage() {
        store.saveEconomy(new BossReviewEconomy(true,7,10000));
        when(ai.sendJobReviewRequest(anyString(),anyBoolean(),anyInt())).thenReturn(AiService.JobReviewResponse.untracked("invalid JSON"));
        String run=service.preview("examples",8,null);awaitFinished(run);
        assertEquals("budget_reached",store.run(run,"all").orElseThrow().state());assertEquals(1,store.usage(run).unreportedRequests());
        verify(ai,times(1)).sendJobReviewRequest(anyString(),anyBoolean(),anyInt());
    }

    @Test void cachedApprovalCannotBypassCurrentHrActivityAndUnknownHrDoesNotCostTokens() {
        when(ai.sendJobReviewRequest(anyString(),anyBoolean(),anyInt())).thenAnswer(call -> approve(call.getArgument(0)));
        var config=new BossConfigEntity();config.setFilterDeadHr(1);when(boss.getFirstConfig()).thenReturn(config);
        var context=service.context(null,false);var base=BossReviewStoreTest.job();
        var fresh=new BossReviewJob(base.id(),base.title(),base.company(),base.city(),base.salary(),base.experience(),base.degree(),"","刚刚活跃",base.description(),base.detailUrl(),base.keyword());
        String first=service.begin("scan",context);service.analyze(first,context,List.of(fresh),()->false,ignored -> {});
        assertEquals(1,store.run(first,"all").orElseThrow().passed());
        var stale=new BossReviewJob(base.id(),base.title(),base.company(),base.city(),base.salary(),base.experience(),base.degree(),"","本周活跃",base.description(),base.detailUrl(),base.keyword());
        String second=service.begin("scan",context);service.analyze(second,context,List.of(stale),()->false,ignored -> {});
        assertEquals(1,store.run(second,"all").orElseThrow().rejected());assertEquals(0,store.usage(second).requests());assertEquals(0,store.usage(second).reusedJobs());
        String unknown=service.begin("scan",context);service.analyze(unknown,context,List.of(base),()->false,ignored -> {});
        assertEquals(1,store.run(unknown,"all").orElseThrow().pending());assertEquals(0,store.usage(unknown).requests());
        config.setFilterDeadHr(0);var disabled=service.context(null,false);assertEquals(context.fingerprint(),disabled.fingerprint());
        String off=service.begin("scan",disabled);service.analyze(off,disabled,List.of(stale),()->false,ignored -> {});
        assertEquals(1,store.run(off,"all").orElseThrow().passed());assertEquals(1,store.usage(off).reusedJobs());
        verify(ai,times(1)).sendJobReviewRequest(anyString(),anyBoolean(),anyInt());
    }

    private static AiService.JobReviewResponse approve(String prompt) {
        var inputs=new org.json.JSONArray(prompt.substring(prompt.indexOf("岗位数据：")+5));var rows=new org.json.JSONArray();
        for(Object input:inputs)rows.put(new org.json.JSONObject().put("id",((org.json.JSONObject)input).getString("id"))
                .put("decision","pass").put("category","AI Agent 智能体开发").put("matchesGoal",true).put("reason","主要职责为Agent服务开发")
                .put("evidence",new org.json.JSONArray().put("负责开发 Agent 后端服务")));
        return new AiService.JobReviewResponse(new org.json.JSONObject().put("results",rows).toString(),new AiService.TokenUsage(700,400,1100,0,500),"deepseek-flash","stop",true);
    }
    private void awaitFinished(String id) {
        long account=BossAccountContext.id();
        assertTimeoutPreemptively(Duration.ofSeconds(8),()-> {
            try(var scope=BossAccountContext.use(account)) {
                while("running".equals(store.run(id,"all").orElseThrow().state()))Thread.sleep(10);
            }
        });
    }
}
