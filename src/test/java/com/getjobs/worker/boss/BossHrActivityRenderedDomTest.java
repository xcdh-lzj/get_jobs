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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用实际 SSR 招聘者结构重放，不依赖详情接口返回活跃时间；所有网络和 AI 均模拟。 */
@EnabledIfEnvironmentVariable(named="BOSS_CHAT_DOM_TEST",matches="true")
class BossHrActivityRenderedDomTest {
    @TempDir Path directory;
    private static final String JD="负责使用 Python 开发 Agent 后端服务，集成 RAG 与工具调用，完成测试和部署。";
    @Test void serverRenderedAndDelayedActivityWorksWithoutDetailApiAndDoesNotReadOtherRecruiters() throws Exception {
        String actual;
        try(var input=getClass().getResourceAsStream("/boss/hr-online-detail.html")) {assertNotNull(input);actual=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
        var html=new LinkedHashMap<String,String>();
        html.put("online",actual);
        html.put("recent","<div class='job-boss-info'><h2 class='name'>招聘者 <span class='boss-active-time'>刚刚活跃</span></h2></div>");
        html.put("three","<div class='job-boss-info'><h2 class='name'>招聘者 <span class='boss-active-time'>3日内活跃</span></h2></div>");
        html.put("late","<div class='job-boss-info'><h2 class='name'>招聘者<span id='later' class='boss-active-time'></span></h2></div><script>setTimeout(()=>{document.querySelector('#later').textContent='刚刚活跃'},600)</script>");
        html.put("week","<div class='job-boss-info'><h2 class='name'>招聘者 <span class='boss-active-time'>本周活跃</span></h2></div>");
        html.put("month","<div class='job-boss-info'><h2 class='name'>招聘者 <span class='boss-active-time'>本月活跃</span></h2></div>");
        html.put("missing","");
        html.put("unknown","<div class='job-boss-info'><h2 class='name'>招聘者<span class='boss-active-time'>活跃时间未知</span></h2></div>");
        html.put("hidden","<div class='job-boss-info' style='display:none'><h2 class='name'>招聘者<span class='boss-online-tag'>在线</span></h2></div>");
        html.put("other-layout","<div class='job-boss'><h2 class='name'>招聘者<em>今天活跃</em></h2></div>");
        html.put("list-online","");html.put("api-online","");
        var expected=List.of("online","recent","three","late","other-layout","list-online","api-online");
        var env=new HashMap<>(System.getenv());env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1");
        try(var pw=Playwright.create(new Playwright.CreateOptions().setEnv(env));var browser=pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setExecutablePath(Path.of(System.getenv("BOSS_TEST_CHROME"))))) {
            for(boolean formal:List.of(false,true))try(var context=browser.newContext()) {
                var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve(formal?"formal.db":"scan.db"));
                var store=new BossReviewStore(new JdbcTemplate(source),new ObjectMapper());store.migrate();
                var data=mock(BossService.class);var ai=mock(AiService.class);var hr=mock(BossHrSettingsService.class);
                when(hr.policy()).thenReturn(BossHrActivityPolicy.defaults());
                var saved=new BossConfigEntity();saved.setFilterDeadHr(1);when(data.getFirstConfig()).thenReturn(saved);
                when(data.getBlackCompanies()).thenReturn(Set.of());when(data.getBlackJobs()).thenReturn(Set.of());when(data.getBlackRecruiters()).thenReturn(Set.of());
                when(data.toNames(anyString(),anyList())).thenReturn(List.of());when(data.normalizeCityToName(anyString())).thenReturn("广州");
                List<String> reviewed=Collections.synchronizedList(new ArrayList<>()),contacts=new ArrayList<>(),messages=new ArrayList<>();
                var detailApis=new AtomicInteger();var sends=new AtomicInteger();var outside=new AtomicInteger();
                when(ai.sendJobReviewRequest(anyString(),anyBoolean(),anyInt())).thenAnswer(call -> {
                    String prompt=call.getArgument(0);var inputs=new JSONArray(prompt.substring(prompt.indexOf("岗位数据：")+5));var rows=new JSONArray();
                    for(Object raw:inputs){var input=(JSONObject)raw;reviewed.add(input.getString("title").substring(8));rows.put(new JSONObject().put("id",input.getString("id")).put("decision","pass").put("category","AI Agent 智能体开发").put("matchesGoal",true).put("reason","主要负责Agent服务研发").put("evidence",new JSONArray().put("负责使用 Python 开发 Agent 后端服务")));}
                    return new AiService.JobReviewResponse(new JSONObject().put("results",rows).toString(),new AiService.TokenUsage(300,100,400,0,200),"mock-ai","stop",true);
                });
                context.route("**/*",route -> {
                    String url=route.request().url(),body,type="text/html; charset=utf-8";
                    if(!"www.zhipin.com".equals(URI.create(url).getHost())){outside.incrementAndGet();route.abort();return;}
                    if(url.contains("/web/geek/jobs")) {
                        var list=new StringBuilder("<ul class='rec-job-list'>");
                        for(String key:html.keySet())list.append("<li class='job-card-box'><a class='job-name' href='/job_detail/ssr-").append(key).append(".html'>Agent开发 ").append(key).append("</a><span class='company-name'>模拟公司</span><span class='job-area'>广州</span><span class='salary'>20-30K</span>").append(key.equals("list-online")?"<span class='boss-online-tag'>在线</span>":"").append("</li>");
                        body=list.append("</ul><div id='footer'>到底部</div>").toString();
                    }else if(url.contains("/wapi/zpgeek/job/detail.json")) {
                        detailApis.incrementAndGet();
                        body=new JSONObject().put("zpData",new JSONObject().put("jobInfo",new JSONObject().put("encryptId","ssr-api-online").put("jobName","Agent开发 api-online").put("locationName","广州").put("salaryDesc","20-30K").put("postDescription",JD))
                                .put("bossInfo",new JSONObject().put("bossOnline",true).put("activeTimeDesc",JSONObject.NULL)).put("brandComInfo",new JSONObject().put("brandName","模拟公司"))).toString();type="application/json; charset=utf-8";
                    }else if(url.contains("mock-contact")){contacts.add(URI.create(url).getQuery().substring(3));body="ok";}
                    else if(url.contains("mock-send")){sends.incrementAndGet();body="ok";}
                    else {
                        String id=URI.create(url).getPath().replace("/job_detail/","").replace(".html",""),key=id.substring(4);
                        body=html.get(key)+"<aside><div class='job-boss-info'><h2 class='name'>其他岗位招聘者<span class='boss-online-tag'>在线</span></h2></div></aside>";
                        if(key.equals("api-online"))body+="<script>fetch('/wapi/zpgeek/job/detail.json').then(r=>r.json()).then(d=>{let e=document.createElement('div');e.className='job-sec-text';e.textContent=d.zpData.jobInfo.postDescription;document.body.appendChild(e);})</script>";
                        else body+="<div class='job-sec-text'>"+JD+"该服务为在线平台。</div>";
                        body+="<a class='btn-startchat' onclick=\"fetch('/mock-contact?id="+id+"');document.querySelector('#chat').style.display='block'\">立即沟通</a><div id='chat' style='display:none'><textarea class='input-area'></textarea><button class='btn-send' onclick=\"fetch('/mock-send',{method:'POST',body:document.querySelector('textarea').value})\">发送</button></div>";
                    }
                    route.fulfill(new Route.FulfillOptions().setContentType(type).setBody(body));
                });
                var reviews=new BossReviewService(store,new BossJobReviewer(ai),data,new BossAccountLocks(),hr);
                var config=new BossConfig();config.setCityCode(List.of("101280100"));config.setKeywords(List.of("Agent"));config.setWaitTime("1");config.setFilterDeadHR(true);config.setSayHi("您好，希望了解这个研发岗位。");
                var worker=new Boss(data,ai,null,reviews,null,hr);worker.setConfig(config);worker.setPage(context.newPage());worker.setReviewOnly(!formal);worker.setShouldStopCallback(()->false);worker.setProgressCallback((message,current,total)->messages.add(message));worker.prepare();
                assertEquals(formal?7:0,worker.execute());var report=store.latest("all").orElseThrow();
                assertEquals(12,report.total());assertEquals(7,report.passed());assertEquals(2,report.rejected());assertEquals(3,report.pending());
                assertEquals(new HashSet<>(expected),new HashSet<>(reviewed));assertEquals(7,reviewed.size());
                assertEquals(formal?expected.stream().map(x->"ssr-"+x).toList():List.of(),contacts);assertEquals(formal?7:0,sends.get());
                assertEquals(formal?2:1,detailApis.get());assertEquals(0,outside.get());
                for(var row:report.rows())if(row.result().approved())assertFalse(row.result().job().activeStatus().isBlank());
                String proof=System.getenv("BOSS_HR_READER_PROOF_DIR");if(proof!=null){Files.createDirectories(Path.of(proof));Files.writeString(Path.of(proof,formal?"rendered-formal.json":"rendered-scan.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("reviewed",reviewed,"mockContacts",contacts,"externalContacts",0,"messages",messages,"run",report)));}
            }
        }
    }
}
