package com.getjobs.application.service;

import com.sun.net.httpserver.HttpServer;
import com.getjobs.worker.boss.review.*;
import org.json.*;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiReviewCostTest {
    private HttpServer server;
    private AiService ai;
    private final ConfigService config=mock(ConfigService.class);
    private final AtomicReference<JSONObject> request=new AtomicReference<>();
    private final AtomicReference<String> response=new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/",exchange -> {
            request.set(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start(); configure("deepseek-flash");
        ai=new AiService(config,mock(com.getjobs.application.mapper.AiMapper.class));
        response.set(reply("{}","stop",true));
    }
    private void configure(String model) {
        when(config.getAiConfigs()).thenReturn(Map.of("BASE_URL","http://127.0.0.1:"+server.getAddress().getPort(),"API_KEY","local-test-only","MODEL",model));
    }
    @AfterEach void close(){server.stop(0);}
    private static String reply(String content,String finish,boolean usage) {
        var body=new JSONObject().put("model","deepseek-flash").put("choices",new JSONArray().put(new JSONObject()
                .put("finish_reason",finish).put("message",new JSONObject().put("content",content))));
        if(usage)body.put("usage",new JSONObject().put("prompt_tokens",1000).put("completion_tokens",120).put("total_tokens",1120)
                .put("prompt_cache_hit_tokens",800).put("completion_tokens_details",new JSONObject().put("reasoning_tokens",0)));
        return body.toString();
    }
    @Test void economyDisablesDeepseekThinkingCapsOutputAndReportsTokenBreakdown() {
        var result=ai.sendJobReviewRequest("只输出JSON",true,960);
        assertEquals("disabled",request.get().getJSONObject("thinking").getString("type"));
        assertEquals(960,request.get().getInt("max_tokens"));assertTrue(result.economyApplied());
        assertEquals(new AiService.TokenUsage(1000,120,1120,0,800),result.usage());
        ai.sendJobReviewRequest("常规审核",false,8192);
        assertFalse(request.get().has("thinking"));assertEquals(8192,request.get().getInt("max_tokens"));
        ai.sendReviewRequest("简历分析或招呼语");
        assertFalse(request.get().has("thinking"));assertFalse(request.get().has("max_tokens"));
    }
    @Test void unsupportedProviderKeepsItsParametersAndMissingUsageIsUnknown() {
        configure("another-chat-model");response.set(reply("{}","stop",false));
        var result=ai.sendJobReviewRequest("测试",true,800);
        assertFalse(request.get().has("thinking"));assertEquals(800,request.get().getInt("max_tokens"));
        assertFalse(result.economyApplied());assertFalse(result.usage().known());assertEquals(-1,result.usage().totalTokens());
    }
    @Test void truncatedEvenOtherwiseValidJsonNeverBecomesAnApprovalAndStillCountsUsage() {
        response.set(reply("{\"results\":[]}","length",true));
        var reviewer=new BossJobReviewer(ai);var results=new ArrayList<BossReviewResult>();var usages=new ArrayList<BossJobReviewer.RequestUsage>();
        var job=new BossReviewJob("j","Agent研发","公司","广州","20-30K","","","","","负责编码开发 Agent 与 RAG 服务。","","");
        reviewer.review(List.of(job),BossReviewPolicy.defaults(),()->false,results::add,
                new BossJobReviewer.Execution(BossReviewEconomy.defaults(),0,false,usages::add));
        assertEquals("review",results.getFirst().decision());assertTrue(results.getFirst().reason().contains("截断"));
        assertTrue(usages.getFirst().failed());assertEquals(1120,usages.getFirst().tokens().totalTokens());
    }

    @Test void emptyContentStillRetainsProviderUsageInsteadOfLosingBilledTokens() {
        var body=new JSONObject(reply("","stop",true));
        body.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("content",JSONObject.NULL);
        response.set(body.toString());
        var result=ai.sendJobReviewRequest("测试空响应",true,800);
        assertEquals("",result.content());assertEquals(1120,result.usage().totalTokens());
    }

    @Test void responsesIncompleteStatusAndAlternateUsageFieldsAreRecorded() {
        configure("o3");response.set(new JSONObject().put("model","o3").put("status","incomplete").put("output_text","{\"results\":[]}")
                .put("usage",new JSONObject().put("input_tokens",900).put("output_tokens",100).put("total_tokens",1000)).toString());
        var result=ai.sendJobReviewRequest("测试截断响应",true,800);
        assertEquals("incomplete",result.finishReason());assertEquals(1000,result.usage().totalTokens());
        assertEquals(800,request.get().getInt("max_output_tokens"));assertFalse(request.get().has("thinking"));
    }
}
