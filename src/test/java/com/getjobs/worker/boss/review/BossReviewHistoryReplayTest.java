package com.getjobs.worker.boss.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.AiService;
import org.json.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 离线回放既有语义结论和证据，检查请求重构后的身份映射与安全校验；不评价新模型准确率。 */
@EnabledIfEnvironmentVariable(named="BOSS_REVIEW_HISTORY_DB",matches=".+")
class BossReviewHistoryReplayTest {
    @Test void previousDecisionsAndEvidenceSurviveOfflineReplayWithoutAnExternalAiCall() throws Exception {
        var json=new ObjectMapper();var ai=mock(AiService.class);var reviewer=new BossJobReviewer(ai);int count=0;
        var decisions=new LinkedHashMap<String,Integer>();
        try(var db=DriverManager.getConnection("jdbc:sqlite:file:"+System.getenv("BOSS_REVIEW_HISTORY_DB")+"?mode=ro");
            var query=db.createStatement();var rows=query.executeQuery("SELECT result_json,policy_json FROM boss_review_results r JOIN boss_review_runs t ON r.run_id=t.id AND r.account_id=t.account_id WHERE r.decision IN ('pass','reject') AND json_extract(result_json,'$.stage')='semantic' ORDER BY r.id")) {
            while(rows.next()) {
                var original=json.readValue(rows.getString(1),BossReviewResult.class);var policy=json.readValue(rows.getString(2),BossReviewPolicy.class).validated();
                when(ai.sendJobReviewRequest(anyString(),eq(true),anyInt())).thenAnswer(call -> {
                    String prompt=call.getArgument(0);var input=new JSONArray(prompt.substring(prompt.indexOf("岗位数据：")+5)).getJSONObject(0);
                    var row=new JSONObject().put("id",input.getString("id")).put("decision",original.decision()).put("category",original.category())
                            .put("matchesGoal",original.approved()).put("reason",original.reason()).put("evidence",new JSONArray(original.evidence()));
                    return AiService.JobReviewResponse.untracked(new JSONObject().put("results",new JSONArray().put(row)).toString());
                });
                var replay=reviewer.reviewBatch(List.of(original.job()),policy).getFirst();
                assertEquals(original.decision(),replay.decision());assertEquals(original.evidence(),replay.evidence());assertEquals(original.job().key(),replay.job().key());
                count++;decisions.merge(original.decision(),1,Integer::sum);
            }
        }
        assertTrue(count>0);verify(ai,times(count)).sendJobReviewRequest(anyString(),eq(true),anyInt());verify(ai,never()).sendReviewRequest(anyString());
        String proof=System.getenv("BOSS_ECONOMY_PROOF_DIR");
        if(proof!=null){Files.createDirectories(Path.of(proof));Files.writeString(Path.of(proof,"history-replay.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("replayed",count,"decisions",decisions,"externalAiCalls",0,"accuracyBenchmark",false)));}
    }
}
