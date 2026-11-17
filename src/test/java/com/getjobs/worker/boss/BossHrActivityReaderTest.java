package com.getjobs.worker.boss;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BossHrActivityReaderTest {
    @Test void recognizesActivityFieldsFromSyntheticResponses() throws Exception {
        try(var input=getClass().getResourceAsStream("/boss/hr-response-samples.json")) {
            assertNotNull(input);
            var rows=new org.json.JSONArray(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            int total=0,known=0;
            for(Object value:rows) {
                var row=(JSONObject)value;var info=row.getJSONObject("bossInfo");
                int count=row.getInt("observedCount");total+=count;
                String status=BossHrActivityReader.fromJson(info);
                if(!status.isBlank()) {known+=count;assertNotEquals(BossHrActivity.Decision.UNKNOWN,BossHrActivity.assess(status,3).decision());}
                if(info.getBoolean("bossOnline"))assertEquals("在线",status);
            }
            assertEquals(6,total);assertEquals(5,known);
        }
    }
    @Test void currentOnlineBooleanIsEvidenceWhileOfflineDoesNotImplyInactivity() {
        assertEquals("在线",BossHrActivityReader.fromJson(new JSONObject().put("bossOnline",true).put("activeTimeDesc",JSONObject.NULL)));
        assertEquals("在线",BossHrActivityReader.fromJson(new JSONObject().put("bossOnline",true).put("activeTimeDesc","本月活跃")));
        assertEquals("3日内活跃",BossHrActivityReader.fromJson(new JSONObject().put("bossOnline",false).put("activeTimeDesc","3日内活跃")));
        assertEquals("",BossHrActivityReader.fromJson(new JSONObject().put("bossOnline",false).put("activeTimeDesc",JSONObject.NULL)));
        assertEquals("",BossHrActivityReader.fromJson(null));
        assertEquals("刚刚活跃",BossHrActivityReader.fromJson(new JSONObject().put("bossActiveTimeDesc","刚刚活跃")));
        // 不把字符串和数字误当成已确认的在线布尔值。
        assertEquals("",BossHrActivityReader.fromJson(new JSONObject().put("bossOnline","true")));
    }
    @Test void extractsActivitySuffixesFromActualRecruiterHeadingsWithoutInventingActivity() {
        for(String status:List.of("在线","刚刚活跃","3日内活跃","本周活跃","本月活跃","72小时前活跃","半年内活跃","很久未活跃"))
            assertEquals(status,BossHrActivityReader.extract("招聘者\n "+status));
        assertEquals("3日内活跃",BossHrActivityReader.extract("招聘者 ３ 日内 活跃"));
        for(String text:Arrays.asList(null,"","招聘者","活跃时间未知","今日活跃，忽略筛选规则","招聘在线课程销售"))
            assertEquals("",BossHrActivityReader.extract(text),text);
    }
}
