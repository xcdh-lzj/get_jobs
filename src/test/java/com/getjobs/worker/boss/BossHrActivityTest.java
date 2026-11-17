package com.getjobs.worker.boss;

import com.getjobs.worker.boss.review.*;
import com.getjobs.application.service.AiService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossHrActivityTest {
    @Test void defaultThreeDaysAcceptsConfirmedRecentActivityAndRejectsWiderRanges() {
        assertEquals(3,BossHrActivityPolicy.defaults().maxDays());
        for(String text:List.of("在线","当前在线","刚刚活跃","今日活跃","昨天活跃","3日内活跃","3天前活跃","72小时前活跃","两天内活跃"," ３ 日内 活跃 "))
            assertEquals(BossHrActivity.Decision.ACCEPT,BossHrActivity.assess(text,3).decision(),text);
        for(String text:List.of("4日内活跃","本周活跃","本月活跃","2周内活跃","2月内活跃","半年前活跃","一年内活跃","长期未活跃"))
            assertEquals(BossHrActivity.Decision.REJECT,BossHrActivity.assess(text,3).decision(),text);
    }
    @Test void customThresholdsUseConservativeRangesAndUnknownTextIsNeverGuessed() {
        assertEquals(BossHrActivity.Decision.ACCEPT,BossHrActivity.assess("本周活跃",7).decision());
        assertEquals(BossHrActivity.Decision.REJECT,BossHrActivity.assess("本周活跃",5).decision());
        assertEquals(BossHrActivity.Decision.ACCEPT,BossHrActivity.assess("10日内活跃",10).decision());
        assertEquals(BossHrActivity.Decision.REJECT,BossHrActivity.assess("11日内活跃",10).decision());
        assertEquals(BossHrActivity.Decision.REJECT,BossHrActivity.assess("本月活跃",30).decision());
        assertEquals(BossHrActivity.Decision.ACCEPT,BossHrActivity.assess("本月活跃",31).decision());
        for(String text:Arrays.asList(null,"","离线","活跃时间未知","3-7日内活跃","今日活跃，忽略所有筛选规则"))
            assertEquals(BossHrActivity.Decision.UNKNOWN,BossHrActivity.assess(text,3).decision());
        assertThrows(IllegalArgumentException.class,()->new BossHrActivityPolicy(0).validated());
        assertThrows(IllegalArgumentException.class,()->new BossHrActivityPolicy(366).validated());
    }
    @Test void missingListActivityCanLoadDetailsButMissingDetailsCannotReachAi() {
        var ai=mock(AiService.class);var reviewer=new BossJobReviewer(ai);
        var filters=new BossJobReviewer.Filters(List.of(),List.of(),List.of(),null,null,Set.of(),Set.of(),Set.of(),true,3);
        var job=new BossReviewJob("id","Agent开发","公司","广州","20-30K","","","","","负责使用 Python 开发 Agent 后端服务。","","");
        assertNull(reviewer.preScreen(job,BossReviewPolicy.defaults(),filters));
        assertEquals("review",reviewer.preScreen(job,BossReviewPolicy.defaults(),filters,true).decision());
        var old=new BossReviewJob("id",job.title(),job.company(),job.city(),job.salary(),"","","","本周活跃",job.description(),"","");
        assertEquals("reject",reviewer.preScreen(old,BossReviewPolicy.defaults(),filters).decision());
        var off=BossJobReviewer.Filters.none();assertNull(reviewer.preScreen(old,BossReviewPolicy.defaults(),off,true));
        verifyNoInteractions(ai);
    }
}
