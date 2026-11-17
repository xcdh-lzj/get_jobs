package com.getjobs.worker.boss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.*;
import com.getjobs.worker.boss.review.*;
import com.microsoft.playwright.*;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="BOSS_CHAT_DOM_TEST",matches="true")
class BossPrecisionDomTest {
    @TempDir Path directory;
    @Test void scanNeverContactsAndFormalModeContactsOnlyReviewedDevelopmentJobs() {
        var environment=new HashMap<>(System.getenv());environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1");
        try(var pw=Playwright.create(new Playwright.CreateOptions().setEnv(environment));
            var browser=pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setExecutablePath(Path.of(System.getenv("BOSS_TEST_CHROME"))))) {
            for(String scenario:List.of("scan","delivery","ai_failure","quota_reminder"))try(var context=browser.newContext()) {
                boolean scan="scan".equals(scenario),unavailable="ai_failure".equals(scenario);
                boolean quota="quota_reminder".equals(scenario);
                boolean shouldDeliver=!scan && !unavailable;
                var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("review-"+scenario+".db"));
                var store=new BossReviewStore(new JdbcTemplate(source),new ObjectMapper());store.migrate();
                var data=mock(BossService.class);var ai=mock(AiService.class);
                when(data.getBlackCompanies()).thenReturn(Set.of());when(data.getBlackJobs()).thenReturn(Set.of());when(data.getBlackRecruiters()).thenReturn(Set.of());
                when(data.toNames(anyString(),anyList())).thenReturn(List.of());
                when(ai.sendReviewRequest(anyString())).thenReturn("{\"decision\":\"pass\"}");
                when(ai.sendJobReviewRequest(anyString(),anyBoolean(),anyInt())).thenAnswer(c -> {
                    if(unavailable)throw new IllegalStateException("simulated provider outage");
                    String prompt=c.getArgument(0);
                    var jobs=new JSONArray(prompt.substring(prompt.indexOf("岗位数据：")+5));var rows=new JSONArray();
                    for(Object value:jobs)rows.put(new JSONObject().put("id",((JSONObject)value).getString("id")).put("decision","pass")
                            .put("category","AI Agent 智能体开发").put("matchesGoal",true).put("reason","主要负责Agent应用开发")
                            .put("evidence",new JSONArray().put("负责使用 Python 开发 Agent 后端服务")));
                    return AiService.JobReviewResponse.untracked(new JSONObject().put("results",rows).toString());
                });
                var reviews=new BossReviewService(store,new BossJobReviewer(ai),data,new BossAccountLocks(),null);
                AtomicInteger salesDetails=new AtomicInteger(),contacts=new AtomicInteger(),sends=new AtomicInteger();
                AtomicInteger reminderAcks=new AtomicInteger();
                AtomicReference<String> sentText=new AtomicReference<>();
                String list="<ul class='rec-job-list'><li class='job-card-box'><a class='job-name' href='/job_detail/sales-job-1.html'>AI大模型销售经理</a><span class='company-name'>公司</span></li><li class='job-card-box'><a class='job-name' href='/job_detail/agent-job-1.html'>AI Agent开发工程师</a><span class='company-name'>公司</span></li></ul><div id='footer'>已到底部</div>";
                String detail="<div class='job-sec-text'>负责使用 Python 开发 Agent 后端服务，集成 RAG 和工具调用，完成接口测试与部署。</div><a class='btn-startchat' onclick=\"fetch('/mock-contact');document.querySelector('#"+(quota?"quota":"chat")+"').style.display='block'\">立即沟通</a>"
                        +"<div id='quota' class='dialog-wrap' role='dialog' style='display:none'><h3>温馨提示</h3><p>您今天已与120位BOSS沟通，还剩30次沟通机会哦</p><button onclick=\"fetch('/mock-quota-ack');document.querySelector('#quota').style.display='none';document.querySelector('#chat').style.display='block'\">好</button></div>"
                        +"<div id='chat' style='display:none'><textarea class='input-area'></textarea><button class='btn-send' onclick=\"fetch('/mock-send',{method:'POST',body:document.querySelector('textarea').value})\">发送</button></div>";
                context.route("https://www.zhipin.com/**",route -> {
                    String url=route.request().url();
                    if(url.contains("sales-job"))salesDetails.incrementAndGet();
                    if(url.contains("mock-contact")) {contacts.incrementAndGet();assertEquals(2,store.latest("all").orElseThrow().total());}
                    if(url.contains("mock-send")) {sends.incrementAndGet();sentText.set(route.request().postData());}
                    if(url.contains("mock-quota-ack"))reminderAcks.incrementAndGet();
                    route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(url.contains("/web/geek/jobs")?list:detail));
                });
                Page page=context.newPage();var boss=new Boss(data,ai,null,reviews,null,null);boss.setPage(page);boss.setReviewOnly(scan);
                var config=new BossConfig();config.setCityCode(List.of("101280100"));config.setKeywords(List.of("Agent"));config.setWaitTime("1");config.setSayHi("您好，希望了解该开发岗位。");config.setEnableAI(true);
                boss.setConfig(config);boss.setShouldStopCallback(()->false);boss.setProgressCallback((message,current,total)->{});boss.prepare();
                assertEquals(shouldDeliver?1:0,boss.execute());
                assertEquals(0,salesDetails.get());assertEquals(shouldDeliver?1:0,contacts.get());assertEquals(shouldDeliver?1:0,sends.get());
                assertEquals(quota?1:0,reminderAcks.get());
                if(shouldDeliver)assertEquals(config.getSayHi(),sentText.get());
                var report=store.latest("all").orElseThrow();assertEquals(2,report.total());assertEquals(unavailable?0:1,report.passed());assertEquals(1,report.rejected());assertEquals(shouldDeliver?1:0,report.delivered());assertEquals(unavailable?1:0,report.pending());
            }
        }
    }
}
