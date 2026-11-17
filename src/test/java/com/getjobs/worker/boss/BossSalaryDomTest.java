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

/** 全部网络由 route 模拟，不使用真实登录态、AI 或招聘者。 */
@EnabledIfEnvironmentVariable(named="BOSS_CHAT_DOM_TEST",matches="true")
class BossSalaryDomTest {
    @TempDir Path directory;
    private static final String JD="负责使用 Python 开发 Agent 后端服务，集成 RAG 与工具调用，完成测试和部署。";
    private record Sample(String list,String detail,String dom) {}

    @Test void encodedSalaryAndDetailFallbackContinuePastUnknownJobsWithoutHumanIntervention() throws Exception {
        var samples=new LinkedHashMap<String,Sample>();
        samples.put("encoded",new Sample("\uE032\uE036-\uE033\uE036K","\uE032\uE036-\uE033\uE036K",""));
        samples.put("legacy",new Sample("\uE8F2\uE8F0-\uE8F2\uE8F5K","20-25K",""));
        samples.put("detail",new Sample("\uE1005-25K","20-25K",""));
        samples.put("dom",new Sample("-K","-K","15–20K"));
        samples.put("missing",new Sample("","",""));
        samples.put("negotiable",new Sample("面议","面议",""));
        samples.put("low",new Sample("\uE032\uE031-\uE032\uE033K","10-12K",""));
        samples.put("high",new Sample("\uE034\uE031-\uE036\uE031K","30-50K",""));
        samples.put("daily",new Sample("200-300元/天","200-300元/天",""));
        samples.put("unknown",new Sample("\uE1005-25K","\uE1005-25K",""));
        samples.put("decimal",new Sample("14.5-14.9K","14.5-14.9K",""));
        samples.put("after-skips",new Sample("25-30K","25-30K",""));
        var expected=List.of("Agent开发 encoded","Agent开发 legacy","Agent开发 detail","Agent开发 dom","Agent开发 after-skips");
        var env=new HashMap<>(System.getenv());env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1");
        try(var pw=Playwright.create(new Playwright.CreateOptions().setEnv(env));var browser=pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setExecutablePath(Path.of(System.getenv("BOSS_TEST_CHROME"))))) {
            for(boolean formal:List.of(false,true))try(var context=browser.newContext()) {
                var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve(formal?"formal.db":"scan.db"));
                var store=new BossReviewStore(new JdbcTemplate(source),new ObjectMapper());store.migrate();
                var data=mock(BossService.class);var ai=mock(AiService.class);var hr=mock(BossHrSettingsService.class);
                when(hr.policy()).thenReturn(BossHrActivityPolicy.defaults());
                var configEntity=new BossConfigEntity();configEntity.setSalary("[15-25K]");configEntity.setExpectedSalaryMin(15);configEntity.setExpectedSalaryMax(25);configEntity.setFilterDeadHr(1);when(data.getFirstConfig()).thenReturn(configEntity);
                when(data.getBlackCompanies()).thenReturn(Set.of());when(data.getBlackJobs()).thenReturn(Set.of());when(data.getBlackRecruiters()).thenReturn(Set.of());
                when(data.toNames(anyString(),anyList())).thenReturn(List.of());when(data.normalizeCityToName(anyString())).thenReturn("广州");
                List<String> reviewed=Collections.synchronizedList(new ArrayList<>()),contacted=new ArrayList<>(),messages=new ArrayList<>(),details=new ArrayList<>();
                var sends=new AtomicInteger();var outside=new AtomicInteger();
                when(ai.sendJobReviewRequest(anyString(),anyBoolean(),anyInt())).thenAnswer(call -> {
                    String prompt=call.getArgument(0);var inputs=new JSONArray(prompt.substring(prompt.indexOf("岗位数据：")+5));var rows=new JSONArray();
                    for(Object raw:inputs){var input=(JSONObject)raw;reviewed.add(input.getString("title"));rows.put(new JSONObject().put("id",input.getString("id")).put("decision","pass").put("category","AI Agent 智能体开发").put("matchesGoal",true).put("reason","主要负责Agent服务研发").put("evidence",new JSONArray().put("负责使用 Python 开发 Agent 后端服务")));}
                    return new AiService.JobReviewResponse(new JSONObject().put("results",rows).toString(),new AiService.TokenUsage(300,100,400,0,200),"mock-ai","stop",true);
                });
                context.route("**/*",route -> {
                    String url=route.request().url(),body,type="text/html; charset=utf-8";
                    if(!"www.zhipin.com".equals(URI.create(url).getHost())){outside.incrementAndGet();route.abort();return;}
                    if(url.contains("/web/geek/jobs")) {
                        var list=new StringBuilder("<ul class='rec-job-list'>");
                        for(var entry:samples.entrySet())list.append("<li class='job-card-box'><a class='job-name' href='/job_detail/salary-").append(entry.getKey()).append(".html'>Agent开发 ").append(entry.getKey()).append("</a><span class='company-name'>模拟公司</span><span class='job-area'>广州</span><span class='job-salary'>").append(entry.getValue().list()).append("</span></li>");
                        body=list.append("</ul><div id='footer'>到底部</div>").toString();
                    }else if(url.contains("/wapi/zpgeek/job/detail.json")) {
                        String id=URI.create(url).getQuery().substring(3),key=id.substring(7);details.add(key);
                        body=new JSONObject().put("zpData",new JSONObject().put("jobInfo",new JSONObject().put("encryptId",id).put("jobName","Agent开发 "+key).put("locationName","广州").put("salaryDesc",samples.get(key).detail()).put("postDescription",JD))
                                .put("bossInfo",new JSONObject().put("activeTimeDesc","刚刚活跃")).put("brandComInfo",new JSONObject().put("brandName","模拟公司"))).toString();type="application/json; charset=utf-8";
                    }else if(url.contains("mock-contact")){contacted.add(URI.create(url).getQuery().substring(3));body="ok";}
                    else if(url.contains("mock-send")){sends.incrementAndGet();body="ok";}
                    else {
                        String id=URI.create(url).getPath().replace("/job_detail/","").replace(".html",""),key=id.substring(7);
                        body="<div class='job-banner'><span class='salary'>"+samples.get(key).dom()+"</span></div><aside class='recommend'><span class='salary'>20-25K</span></aside>"
                                +"<script>fetch('/wapi/zpgeek/job/detail.json?id="+id+"').then(r=>r.json()).then(d=>{let e=document.createElement('div');e.className='job-sec-text';e.textContent=d.zpData.jobInfo.postDescription;document.body.appendChild(e);})</script>"
                                +"<a class='btn-startchat' onclick=\"fetch('/mock-contact?id="+id+"');document.querySelector('#chat').style.display='block'\">立即沟通</a><div id='chat' style='display:none'><textarea class='input-area'></textarea><button class='btn-send' onclick=\"fetch('/mock-send',{method:'POST',body:document.querySelector('textarea').value})\">发送</button></div>";
                    }
                    route.fulfill(new Route.FulfillOptions().setContentType(type).setBody(body));
                });
                var reviews=new BossReviewService(store,new BossJobReviewer(ai),data,new BossAccountLocks(),hr);
                var config=new BossConfig();config.setCityCode(List.of("101280100"));config.setKeywords(List.of("Agent"));config.setWaitTime("1");config.setFilterDeadHR(true);config.setExpectedSalary(List.of(15,25));config.setSayHi("您好，希望了解这个研发岗位。");
                var worker=new Boss(data,ai,null,reviews,null,hr);worker.setConfig(config);worker.setPage(context.newPage());worker.setReviewOnly(!formal);worker.setShouldStopCallback(()->false);worker.setProgressCallback((message,current,total)->messages.add(message));worker.prepare();
                assertEquals(formal?5:0,worker.execute());var report=store.latest("all").orElseThrow();
                assertEquals(12,report.total());assertEquals(5,report.passed());assertEquals(3,report.rejected());assertEquals(4,report.pending());
                assertEquals(new HashSet<>(expected),new HashSet<>(reviewed));assertEquals(5,reviewed.size());
                assertFalse(details.contains("low"));assertFalse(details.contains("high"));assertFalse(details.contains("decimal"));
                assertEquals(formal?expected.stream().map(x->"salary-"+x.substring(8)).toList():List.of(),contacted);
                assertEquals(formal?5:0,sends.get());assertEquals(0,outside.get());
                assertEquals(4,messages.stream().filter(x->x.startsWith("自动跳过（未投递）")).count());
                assertTrue(messages.stream().noneMatch(x->x.contains("待审核") || x.contains("人工")));
                String proof=System.getenv("BOSS_SALARY_PROOF_DIR");if(proof!=null){Files.createDirectories(Path.of(proof));Files.writeString(Path.of(proof,formal?"formal.json":"scan.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("aiReviewed",reviewed,"mockContacts",contacted,"externalContacts",0,"messages",messages,"run",report)));}
            }
        }
    }
}
