package com.getjobs.worker.boss.review;

import com.getjobs.application.service.AiService;
import org.json.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossJobReviewerTest {
    private final AiService ai=mock(AiService.class);
    private final BossJobReviewer reviewer=new BossJobReviewer(ai);
    static BossReviewJob job(String id,String title,String jd) { return new BossReviewJob(id,title,"测试公司","广州","20-30K","1-3年","本科","","",jd,"","测试"); }
    private static final String JD="负责使用 Python 开发 AI Agent 后端服务，集成 RAG 与工具调用，完成接口编码、测试和部署。";

    @Test void customDirectionsCanMatchNonDevelopmentResponsibilitiesWithoutBuiltInRoleRestrictions() throws Exception {
        var policy=new BossReviewPolicy(true,"精投产品策略与用户研究，主要工作为需求调研与产品规划",List.of("产品策略与用户研究"),List.of()).validated();
        var candidate=job("product","产品经理","负责用户需求调研与产品规划，撰写需求文档并跟踪上线效果。");
        assertNull(reviewer.preScreen(candidate,policy,BossJobReviewer.Filters.none()));
        String raw="{\"results\":[{\"id\":\"custom-0\",\"decision\":\"pass\",\"category\":\"产品策略与用户研究\",\"matchesGoal\":true,\"reason\":\"主要职责与产品策略方向一致\",\"evidence\":[\"负责用户需求调研与产品规划\"]}]}";
        assertTrue(reviewer.parse(raw,List.of(candidate),policy,"custom").getFirst().approved());
        assertEquals(3,BossReviewPolicy.defaults().targetRoles().size());
        assertThrows(IllegalArgumentException.class,()->new BossReviewPolicy(true,"目标",List.of(""),List.of()).validated());
    }

    @Test void titlesExcludeActualRolesButPreserveProductDevelopmentAndAmbiguousBackendTitles() {
        var p=BossReviewPolicy.defaults(); var f=BossJobReviewer.Filters.none();
        assertEquals("reject",reviewer.preScreen(job("1","AI产品经理",""),p,f).decision());
        assertEquals("reject",reviewer.preScreen(job("2","大模型销售工程师",""),p,f).decision());
        assertNull(reviewer.preScreen(job("3","AI产品研发工程师",""),p,f));
        assertNull(reviewer.preScreen(job("4","Python后端工程师",""),p,f));
        assertNull(reviewer.preScreen(job("5","销售预测算法工程师",""),p,f));
        verifyNoInteractions(ai);
    }
    @Test void basicConditionsCheckCityExperienceSalaryAndDoNotGuessUnknownSalary() {
        var f=new BossJobReviewer.Filters(List.of("广州"),List.of("1-3年"),List.of(),15.0,35.0,Set.of(),Set.of(),Set.of(),false);
        assertNull(reviewer.preScreen(job("1","Agent开发",JD),BossReviewPolicy.defaults(),f));
        var wrong=new BossReviewJob("2","Agent开发","公司","北京","20-30K","1-3年","本科","","",JD,"","");
        assertEquals("reject",reviewer.preScreen(wrong,BossReviewPolicy.defaults(),f).decision());
        var unknown=new BossReviewJob("3","Agent开发","公司","广州","面议","1-3年","本科","","",JD,"","");
        assertNull(reviewer.preScreen(unknown,BossReviewPolicy.defaults(),f));
        assertEquals("review",reviewer.preScreen(unknown,BossReviewPolicy.defaults(),f,true).decision());
    }
    @Test void parserRequiresTargetRoleDevelopmentAndExactJdEvidence() throws Exception {
        var jobs=List.of(job("1","Agent开发",JD));
        JSONObject row=new JSONObject().put("id","batch-0").put("decision","pass").put("category","AI Agent 智能体开发")
                .put("matchesGoal",true).put("reason","主要职责是Agent服务研发").put("evidence",new JSONArray().put("负责使用 Python 开发 AI Agent 后端服务"));
        String raw=new JSONObject().put("results",new JSONArray().put(row)).toString();
        assertTrue(reviewer.parse(raw,jobs,BossReviewPolicy.defaults(),"batch").getFirst().approved());
        row.put("category","sales");
        assertFalse(reviewer.parse(new JSONObject().put("results",new JSONArray().put(row)).toString(),jobs,BossReviewPolicy.defaults(),"batch").getFirst().approved());
        row.put("category","AI Agent 智能体开发").put("evidence",new JSONArray().put("不存在于原文的开发职责"));
        assertEquals("review",reviewer.parse(new JSONObject().put("results",new JSONArray().put(row)).toString(),jobs,BossReviewPolicy.defaults(),"batch").getFirst().decision());
        row.put("evidence",new JSONArray().put("负责使用 Python 开发 AI Agent 后端服务")).put("matchesGoal",false);
        assertEquals("review",reviewer.parse(new JSONObject().put("results",new JSONArray().put(row)).toString(),jobs,BossReviewPolicy.defaults(),"batch").getFirst().decision());
    }
    @Test void missingDuplicateAndForeignIdsNeverAssignApprovalToAnotherJob() throws Exception {
        var jobs=List.of(job("1","Agent开发",JD));
        assertEquals("review",reviewer.parse("{\"results\":[]}",jobs,BossReviewPolicy.defaults(),"b").getFirst().decision());
        assertThrows(IllegalArgumentException.class,()->reviewer.parse("{\"results\":[{\"id\":\"other\"}]}",jobs,BossReviewPolicy.defaults(),"b"));
        assertThrows(IllegalArgumentException.class,()->reviewer.parse("{\"results\":[{\"id\":\"b-0\"},{\"id\":\"b-0\"}]}",jobs,BossReviewPolicy.defaults(),"b"));
    }
    @Test void unavailableAiMissingJdAndMalformedResultsStayPending() {
        when(ai.sendJobReviewRequest(anyString(),anyBoolean(),anyInt())).thenThrow(new RuntimeException("provider unavailable"));
        var results=new ArrayList<BossReviewResult>();
        reviewer.review(List.of(job("1","Agent开发",JD),job("2","AI开发","")),BossReviewPolicy.defaults(),()->false,results::add);
        assertEquals(2,results.size()); assertTrue(results.stream().allMatch(r->"review".equals(r.decision())));
        verify(ai,times(1)).sendJobReviewRequest(anyString(),anyBoolean(),anyInt());
    }
    @Test void batchesAreBoundedAndReturnedInJobIdentityEvenWhenModelReordersRows() {
        AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
        when(ai.sendJobReviewRequest(anyString(),anyBoolean(),anyInt())).thenAnswer(call -> {
            int now=active.incrementAndGet();maximum.accumulateAndGet(now,Math::max);
            try {
                String prompt=call.getArgument(0); JSONArray inputs=new JSONArray(prompt.substring(prompt.indexOf("岗位数据：")+5));
                assertTrue(inputs.length()<=4);
                JSONArray rows=new JSONArray();
                for(int i=inputs.length()-1;i>=0;i--)rows.put(new JSONObject().put("id",inputs.getJSONObject(i).getString("id"))
                        .put("decision","pass").put("category","AI Agent 智能体开发").put("matchesGoal",true).put("reason","Agent研发职责明确")
                        .put("evidence",new JSONArray().put("负责使用 Python 开发 AI Agent 后端服务")));
                return AiService.JobReviewResponse.untracked(new JSONObject().put("results",rows).toString());
            } finally { active.decrementAndGet(); }
        });
        List<BossReviewJob> jobs=new ArrayList<>(); for(int i=0;i<10;i++)jobs.add(job("id-"+i,"Agent开发"+i,JD));
        var results=new ArrayList<BossReviewResult>(); reviewer.review(jobs,BossReviewPolicy.defaults(),()->false,results::add);
        assertEquals(10,results.size());assertEquals(10,results.stream().map(r->r.job().id()).distinct().count());
        assertTrue(results.stream().allMatch(BossReviewResult::approved)); assertTrue(maximum.get()<=2);
    }
}
