package com.getjobs.worker.boss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.worker.boss.review.BossReviewResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用只读备份核对实际编码与同岗位明文，不重新投递或调用 AI。 */
@EnabledIfEnvironmentVariable(named="BOSS_REVIEW_HISTORY_DB",matches=".+")
class BossSalaryHistoryReplayTest {
    @Test void storedEncodedSalariesRecoverAndMatchPlaintextForTheSameJobs() throws Exception {
        var json=new ObjectMapper();int encoded=0,recovered=0,previouslySkipped=0;
        Map<String,Set<String>> originals=new HashMap<>();
        try(var db=DriverManager.getConnection("jdbc:sqlite:file:"+System.getenv("BOSS_REVIEW_HISTORY_DB")+"?mode=ro");var query=db.createStatement();var rows=query.executeQuery("SELECT result_json FROM boss_review_results")) {
            while(rows.next()) {
                var raw=json.readTree(rows.getString(1));String salary=raw.path("job").path("salary").asText();
                var result=json.readValue(rows.getString(1),BossReviewResult.class);
                originals.computeIfAbsent(result.job().key(),key->new HashSet<>()).add(salary);
                if(salary.chars().anyMatch(c->c>=0xE031 && c<=0xE03A)) {
                    encoded++;
                    var monthly=BossSalary.parse(salary);
                    if(monthly!=null)recovered++;
                    if(monthly!=null && result.reason().contains("薪资信息不明确"))previouslySkipped++;
                    assertFalse(result.job().salary().chars().anyMatch(c->c>=0xE031 && c<=0xE03A));
                }
            }
        }
        int matched=0;
        for(var values:originals.values())for(String raw:values) {
            if(raw.chars().anyMatch(c->c>=0xE031 && c<=0xE03A) && values.contains(BossSalary.normalize(raw)))matched++;
        }
        assertTrue(encoded>0);assertEquals(encoded,recovered);assertTrue(previouslySkipped>0);assertTrue(matched>=13);
        String proof=System.getenv("BOSS_SALARY_PROOF_DIR");
        if(proof!=null){Files.createDirectories(Path.of(proof));Files.writeString(Path.of(proof,"history-replay.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("encodedRecords",encoded,"recoveredRecords",recovered,"previouslySkippedBySalaryBug",previouslySkipped,"sameJobPlaintextMatches",matched,"externalAiCalls",0,"externalContacts",0)));}
    }
}
