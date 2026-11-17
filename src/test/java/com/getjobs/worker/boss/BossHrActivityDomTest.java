package com.getjobs.worker.boss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.application.service.*;
import com.getjobs.worker.boss.review.*;
import com.microsoft.playwright.*;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="BOSS_CHAT_DOM_TEST",matches="true")
class BossHrActivityDomTest {
    @TempDir Path directory;
    private static final String JD="负责使用 Python 开发 Agent 后端服务，集成 RAG 与工具调用，完成测试和部署。";
    @Test void missingListActivityLoadsDetailsAndOnlyRecentVerifiedHrCanReachAiAndContact() throws Exception {
        var env=new HashMap<>(System.getenv());env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1");
        var activity=new LinkedHashMap<String,String>();activity.put("fresh","刚刚活跃");activity.put("week","本周活跃");activity.put("five","5日内活跃");activity.put("month","本月活跃");activity.put("empty","");activity.put("unknown","活跃时间未知");
        try(var pw=Playwright.create(new Playwright.CreateOptions().setEnv(env));var browser=pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setExecutablePath(Path.of(System.getenv("BOSS_TEST_CHROME"))))) {
            for(String scenario:List.of("scan3","scan7","custom5","disabled","formal3"))try(var context=browser.newContext()) {
                int days=scenario.equals("scan7")?7:scenario.equals("custom5")?5:3;
                boolean enabled=!scenario.equals("disabled"),formal=scenario.equals("formal3");
                var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve(scenario+".db"));
                var store=new BossReviewStore(new JdbcTemplate(source),new ObjectMapper());store.migrate();
                var data=mock(BossService.class);var ai=mock(AiService.class);var hr=mock(BossHrSettingsService.class);
                when(hr.policy()).thenReturn(new BossHrActivityPolicy(days));
                var saved=new BossConfigEntity();saved.setFilterDeadHr(enabled?1:0);when(data.getFirstConfig()).thenReturn(saved);
                when(data.getBlackCompanies()).thenReturn(Set.of());when(data.getBlackJobs()).thenReturn(Set.of());when(data.getBlackRecruiters()).thenReturn(Set.of());
                when(data.toNames(anyString(),anyList())).thenReturn(List.of());when(data.normalizeCityToName(anyString())).thenReturn("广州");
                List<String> reviewed=new ArrayList<>(),contacted=new ArrayList<>();var sends=new AtomicInteger();
                when(ai.sendJobReviewRequest(anyString(),anyBoolean(),anyInt())).thenAnswer(call -> {
                    String prompt=call.getArgument(0);var inputs=new JSONArray(prompt.substring(prompt.indexOf("岗位数据：")+5));var rows=new JSONArray();
                    synchronized(reviewed){for(Object raw:inputs){var input=(JSONObject)raw;reviewed.add(input.getString("title"));rows.put(new JSONObject().put("id",input.getString("id")).put("decision","pass").put("category","AI Agent 智能体开发").put("matchesGoal",true).put("reason","主要负责Agent服务研发").put("evidence",new JSONArray().put("负责使用 Python 开发 Agent 后端服务")));}}
                    return new AiService.JobReviewResponse(new JSONObject().put("results",rows).toString(),new AiService.TokenUsage(300,100,400,0,200),"mock-ai","stop",true);
                });
                context.route("https://www.zhipin.com/**",route -> {
                    String url=route.request().url();String body,type="text/html; charset=utf-8";
                    if(url.contains("/web/geek/jobs")) {
                        var list=new StringBuilder("<ul class='rec-job-list'>");
                        for(String id:activity.keySet())list.append("<li class='job-card-box'><a class='job-name' href='/job_detail/hr-").append(id).append(".html'>Agent开发 ").append(id).append("</a><span class='company-name'>模拟公司</span><span class='job-area'>广州</span></li>");
                        body=list.append("</ul><div id='footer'>到底部</div>").toString();
                    }else if(url.contains("/wapi/zpgeek/job/detail.json")) {
                        String id=URI.create(url).getQuery().substring("id=".length());String suffix=id.substring(3);
                        body=new JSONObject().put("zpData",new JSONObject().put("jobInfo",new JSONObject().put("encryptId",id).put("jobName","Agent开发 "+suffix).put("locationName","广州").put("salaryDesc","20-30K").put("postDescription",JD))
                                .put("bossInfo",new JSONObject().put("activeTimeDesc",activity.get(suffix))).put("brandComInfo",new JSONObject().put("brandName","模拟公司"))).toString();type="application/json; charset=utf-8";
                    }else if(url.contains("mock-contact")) {contacted.add(URI.create(url).getQuery());body="ok";}
                    else if(url.contains("mock-send")){sends.incrementAndGet();body="ok";}
                    else {
                        String id=URI.create(url).getPath().replace("/job_detail/","").replace(".html","");
                        body="<script>fetch('/wapi/zpgeek/job/detail.json?id="+id+"').then(r=>r.json()).then(d=>{let e=document.createElement('div');e.className='job-sec-text';e.textContent=d.zpData.jobInfo.postDescription;document.body.appendChild(e);})</script>"
                                +"<a class='btn-startchat' onclick=\"fetch('/mock-contact?id="+id+"');document.querySelector('#chat').style.display='block'\">立即沟通</a><div id='chat' style='display:none'><textarea class='input-area'></textarea><button class='btn-send' onclick=\"fetch('/mock-send',{method:'POST',body:document.querySelector('textarea').value})\">发送</button></div>";
                    }
                    route.fulfill(new Route.FulfillOptions().setContentType(type).setBody(body));
                });
                var reviews=new BossReviewService(store,new BossJobReviewer(ai),data,new BossAccountLocks(),hr);
                var config=new BossConfig();config.setCityCode(List.of("101280100"));config.setKeywords(List.of("Agent"));config.setWaitTime("1");config.setFilterDeadHR(enabled);config.setSayHi("您好，希望了解这个研发岗位。");
                var worker=new Boss(data,ai,null,reviews,null,hr);worker.setConfig(config);worker.setPage(context.newPage());worker.setReviewOnly(!formal);worker.setShouldStopCallback(()->false);worker.setProgressCallback((message,current,total)->{});worker.prepare();
                assertEquals(formal?1:0,worker.execute());var report=store.latest("all").orElseThrow();
                int passed=scenario.equals("disabled")?6:days==7?3:days==5?2:1;
                assertEquals(passed,report.passed(),scenario);assertEquals(enabled?2:0,report.pending(),scenario);assertEquals(passed,reviewed.size(),scenario);
                if(enabled){assertFalse(reviewed.contains("Agent开发 month"));assertFalse(reviewed.contains("Agent开发 empty"));assertFalse(reviewed.contains("Agent开发 unknown"));}
                assertEquals(formal?List.of("id=hr-fresh"):List.of(),contacted);assertEquals(formal?1:0,sends.get());
                String proof=System.getenv("BOSS_HR_PROOF_DIR");if(proof!=null){Files.createDirectories(Path.of(proof));Files.writeString(Path.of(proof,scenario+".json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scenario",scenario,"maxDays",days,"aiReviewed",reviewed,"mockContacts",contacted,"externalContacts",0,"run",report)));}
            }
        }
    }
}
