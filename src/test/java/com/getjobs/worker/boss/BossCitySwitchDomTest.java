package com.getjobs.worker.boss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.*;
import com.getjobs.worker.boss.review.BossJobReviewer;
import com.microsoft.playwright.*;
import org.json.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="BOSS_CHAT_DOM_TEST",matches="true")
class BossCitySwitchDomTest {
    @TempDir Path directory;
    private static final String A="101280100",B="101280600";
    private static final String JD="负责使用 Python 开发 AI Agent 后端服务，集成 RAG 和工具调用，完成接口测试与部署。";
    @Test void advancesCitiesUsingRecentYieldButPreservesFiltersStopsAndQuota() throws Exception {
        var environment=new HashMap<>(System.getenv());environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1");
        try(var pw=Playwright.create(new Playwright.CreateOptions().setEnv(environment));
            var browser=pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setExecutablePath(Path.of(System.getenv("BOSS_TEST_CHROME"))))) {
            for(String scenario:List.of("low","disabled","ai_failure","empty","stop","quota","budget"))try(var context=browser.newContext()) {
                boolean detailScenario=Set.of("ai_failure","budget").contains(scenario);
                var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve(scenario+".db"));
                var store=new BossReviewStore(new JdbcTemplate(source),new ObjectMapper());store.migrate();
                if(scenario.equals("budget"))store.saveEconomy(new com.getjobs.worker.boss.review.BossReviewEconomy(true,7,1000));
                var data=mock(BossService.class);var ai=mock(AiService.class);
                when(data.getBlackCompanies()).thenReturn(Set.of());when(data.getBlackJobs()).thenReturn(Set.of());when(data.getBlackRecruiters()).thenReturn(Set.of());
                when(data.normalizeCityToName(anyString())).thenAnswer(c->A.equals(c.getArgument(0))?"广州":"深圳");
                when(ai.sendJobReviewRequest(anyString(),anyBoolean(),anyInt())).thenAnswer(c->{
                    if("ai_failure".equals(scenario))throw new IllegalStateException("simulated AI outage");
                    String prompt=c.getArgument(0);var inputs=new JSONArray(prompt.substring(prompt.indexOf("岗位数据：")+5));var rows=new JSONArray();
                    for(Object input:inputs)rows.put(new JSONObject().put("id",((JSONObject)input).getString("id")).put("decision","pass").put("category","AI Agent 智能体开发").put("matchesGoal",true).put("reason","主要职责符合智能体应用研发").put("evidence",new JSONArray().put("负责使用 Python 开发 AI Agent 后端服务")));
                    String result=new JSONObject().put("results",rows).toString();
                    return scenario.equals("budget")?new AiService.JobReviewResponse(result,new AiService.TokenUsage(700,400,1100,0,500),"deepseek-flash","stop",true):AiService.JobReviewResponse.untracked(result);
                });
                var reviews=new BossReviewService(store,new BossJobReviewer(ai),data,new BossAccountLocks(),null);
                List<String> searches=new ArrayList<>(),messages=new ArrayList<>();AtomicInteger aDetails=new AtomicInteger(),bDetails=new AtomicInteger(),contacts=new AtomicInteger();
                AtomicReference<BossCityProgress> snapshot=new AtomicReference<>();AtomicBoolean stop=new AtomicBoolean();
                context.route("https://www.zhipin.com/**",route->{
                    String url=route.request().url();String body;
                    if(url.contains("/web/geek/jobs")) {
                        var query=query(url);String city=query.get("city"),keyword=query.get("query");searches.add(city+"|"+keyword);
                        assertEquals("104",query.get("experience"));assertEquals("203",query.get("degree"));assertEquals("405",query.get("salary"));
                        if(A.equals(city) && scenario.equals("empty"))body="<div class='job-list-empty'>暂无相关职位</div>";
                        else {
                            StringBuilder cards=new StringBuilder("<ul class='rec-job-list'>");
                            if(A.equals(city))for(int n=0;n<(detailScenario?9:20);n++) {
                                boolean mismatch=n==0 && !detailScenario;
                                cards.append(card(mismatch?"shared-B":("A-"+keyword+"-"+n),detailScenario||mismatch?"Agent开发工程师":"AI大模型销售经理",mismatch?"深圳":"广州"));
                            }
                            else cards.append(card("shared-B","AI Agent开发工程师","深圳"));
                            body=cards.append("</ul><div id='footer'>到底部</div>").toString();
                            if(A.equals(city) && scenario.equals("quota"))body+="<div>您已达到沟通上限</div>";
                        }
                    }else {
                        if(url.contains("/job_detail/A-"))aDetails.incrementAndGet();else bDetails.incrementAndGet();
                        if(url.contains("mock-contact"))contacts.incrementAndGet();
                        body="<div class='job-sec-text'>"+JD+"</div><a class='btn-startchat' onclick=\"fetch('/mock-contact')\">立即沟通</a>";
                    }
                    route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(body));
                });
                var config=new BossConfig();config.setCityCode(List.of(A,B));config.setKeywords(List.of("Agent","应用"));
                config.setExperience(List.of("104"));config.setDegree(List.of("203"));config.setSalary(List.of("405"));config.setWaitTime("1");
                config.setCitySwitchPolicy(new BossCityPolicy(!scenario.equals("disabled"),5,0));
                var boss=new Boss(data,ai,null,reviews,null,null);boss.setConfig(config);boss.setPage(context.newPage());boss.setReviewOnly(!scenario.equals("budget"));boss.setShouldStopCallback(stop::get);
                boss.setProgressCallback(new Boss.ProgressCallback(){
                    @Override public void accept(String message,Integer current,Integer total){messages.add(message);if(scenario.equals("stop") && message.contains("近期低产出"))stop.set(true);}
                    @Override public void cityProgress(BossCityProgress p){snapshot.set(p);}
                });boss.prepare();
                if(scenario.equals("quota"))assertThrows(BossDailyLimitReachedException.class,boss::execute);
                else if(scenario.equals("budget"))assertThrows(com.getjobs.worker.boss.review.BossReviewBudgetReachedException.class,boss::execute);
                else assertEquals(0,boss.execute());
                assertEquals(0,contacts.get());var progress=snapshot.get();
                if(scenario.equals("stop") || scenario.equals("quota") || scenario.equals("budget")) {
                    assertEquals(List.of(A+"|Agent"),searches);assertEquals("cancelled",progress.cities().get(1).state());
                    assertEquals(scenario.equals("stop")?"stopped":scenario.equals("budget")?"budget_reached":"quota_reached",progress.cities().getFirst().state());
                }else {
                    assertEquals(List.of(A+"|Agent",A+"|应用",B+"|Agent",B+"|应用"),searches);
                    int expected=switch(scenario){case "low"->10;case "disabled"->39;case "ai_failure"->18;default->0;};
                    assertEquals(expected,progress.cities().getFirst().processed(),scenario);
                    assertEquals(scenario.equals("low")?2:0,progress.cities().getFirst().lowYieldKeywords(),scenario);
                    assertEquals(scenario.equals("ai_failure")?0:1,progress.cities().get(1).newJobs());
                    assertEquals(1,bDetails.get());
                }
                if(scenario.equals("ai_failure")){assertEquals(18,aDetails.get());assertEquals(18,progress.cities().getFirst().uncertain());}
                else if(scenario.equals("budget")){assertEquals(5,aDetails.get());assertEquals(1,store.latest("all").orElseThrow().usage().requests());}
                else assertEquals(0,aDetails.get());
                String proof=System.getenv("BOSS_CITY_PROOF_DIR");if(proof!=null){var path=Path.of(proof);Files.createDirectories(path);Files.writeString(path.resolve(scenario+".json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("cityProgress",progress,"searches",searches,"logs",messages,"communicationCount",contacts.get(),"usage",store.latest("all").orElseThrow().usage())));}
            }
        }
    }
    private static String card(String id,String title,String city){return "<li class='job-card-box'><a class='job-name' href='/job_detail/"+id+".html'>"+title+"</a><span class='company-name'>模拟公司</span><span class='job-area'>"+city+"</span></li>";}
    private static Map<String,String> query(String url){var result=new HashMap<String,String>();for(String item:URI.create(url).getRawQuery().split("&")){var pair=item.split("=",2);result.put(pair[0],URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}return result;}
}
