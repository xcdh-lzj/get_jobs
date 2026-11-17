package com.getjobs.application.service;

import org.json.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossResumeTargetServiceTest {
    private final AiService ai=mock(AiService.class);
    private final BossAccountLocks locks=new BossAccountLocks();
    private final BossResumeTargetService service=new BossResumeTargetService(ai,locks);
    private static final String TEXT="邮箱：resume@example.invalid\n技能：Python、FastAPI、RAG。项目：负责开发企业知识库问答服务，使用 Python 实现文档解析、向量检索、RAG 问答和 Agent 工具调用，完成后端接口开发、测试和部署，优化检索召回率并维护线上接口和权限管理。";
    private JSONObject proposal(){return new JSONObject().put("summary","具备知识库与后端服务的研发经验").put("skills",new JSONArray().put("Python").put("RAG"))
            .put("directions",new JSONArray().put(new JSONObject().put("name","企业知识库与检索增强开发").put("reason","项目承担了知识库后端研发职责").put("evidence",new JSONArray().put("负责开发企业知识库问答服务"))))
            .put("goal","精投企业知识库及检索增强应用开发，以JD的后端服务、向量检索和RAG职责判断，不要求职位名称完全相同。").put("excludedTitles",new JSONArray().put("销售经理"));}
    @Test void groundedDirectionsAreDynamicAndOnlyRedactedTextIsSentToAi() {
        when(ai.sendReviewRequest(anyString())).thenAnswer(call->{String prompt=call.getArgument(0);assertFalse(prompt.contains("resume@example.invalid"));return proposal().toString();});
        var result=service.analyze(TEXT);assertEquals("企业知识库与检索增强开发",result.directions().getFirst().name());assertEquals(1,result.directions().size());
    }
    @Test void InventedEvidenceUnsupportedSkillsAndEmptyDirectionsAreNotAdopted() throws Exception {
        var raw=proposal();raw.getJSONArray("directions").getJSONObject(0).put("evidence",new JSONArray().put("负责芯片设计和机器人研发"));
        assertThrows(IllegalArgumentException.class,()->service.parse(raw.toString(),TEXT));
        var invented=proposal().put("skills",new JSONArray().put("芯片设计"));assertThrows(IllegalArgumentException.class,()->service.parse(invented.toString(),TEXT));
        when(ai.sendReviewRequest(anyString())).thenReturn(proposal().put("directions",new JSONArray()).toString());
        assertThrows(ResponseStatusException.class,()->service.analyze(TEXT));
    }
    @Test void shortTextAndReservedAccountsNeverTriggerAiAndFailuresNeverReturnAnInventedProfile() {
        assertThrows(IllegalArgumentException.class,()->service.analyze("Python开发"));
        locks.reserve(List.of(1L));assertThrows(ResponseStatusException.class,()->service.analyze(TEXT));verifyNoInteractions(ai);locks.release();
        when(ai.sendReviewRequest(anyString())).thenThrow(new RuntimeException("timeout"));
        assertThrows(ResponseStatusException.class,()->service.analyze(TEXT));
    }
}
