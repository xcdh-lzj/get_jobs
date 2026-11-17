package com.getjobs.worker.boss;

import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.getjobs.worker.boss.review.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossSalaryTest {
    @Test void decodesActualStoredSalarySamplesIncludingBonusMonthsAndBothFontRanges() {
        assertEquals("15-25K", BossSalary.normalize("\uE032\uE036-\uE033\uE036K"));
        assertEquals("10-15K", BossSalary.normalize("\uE032\uE031-\uE032\uE036K"));
        assertEquals("15-30K·13薪", BossSalary.normalize("\uE032\uE036-\uE034\uE031K·\uE032\uE034薪"));
        assertEquals("9-14K", BossSalary.normalize("\uE03A-\uE032\uE035K"));
        assertEquals("12-24K·14薪", BossSalary.normalize("\uE032\uE033-\uE033\uE035K·\uE032\uE035薪"));
        assertEquals("20-30K", BossSalary.normalize("\uE8F2\uE8F0-\uE8F3\uE8F0K"));
        var salary=BossService.parseSalary("\uE032\uE036-\uE033\uE036K·\uE032\uE034薪");
        assertEquals(15.0,salary.minK);assertEquals(25.0,salary.maxK);
        assertEquals(13,salary.months);assertEquals(260_000L,salary.annualTotal);
    }

    @Test void parsesMonthlyUnitsAndDecimalRangesWithoutDroppingMeaningfulCharacters() {
        for(String text:List.of("１５ — ２５Ｋ · １３薪","15–25k·13薪","1.5-2.5万/月·13薪","15000-25000元/月·13薪","15-25千/月·13薪")) {
            var salary=BossSalary.parse(text);assertNotNull(salary,text);
            assertEquals(15,salary.minK(),0.00001);assertEquals(25,salary.maxK(),0.00001);assertEquals(13,salary.months());
        }
        assertEquals(15.5,BossSalary.parse("15.5K").minK());
        assertEquals(15.5,BossSalary.parse("15.5K").maxK());
        assertEquals(12,BossSalary.parse("15.5K").months());
    }

    @Test void neverParsesPartialEncodedDigitsOrUnspecifiedDailyAnnualAndReversedAmounts() {
        for(String text:Arrays.asList(null,"","面议","15\uE100-25K","\uE1005-25K","15abc-25K","-K","15-25","30-20K","0-20K","15-25K·99薪","200-300元/天","15-25K/天","20-30万/年","15-25K·1x薪"))
            assertNull(BossSalary.parse(text),text);
    }

    @Test void unknownListSalaryDefersToDetailsAndUnknownDetailsSkipWithoutAiOrHumanWait() {
        var ai=mock(AiService.class);var reviewer=new BossJobReviewer(ai);
        var filters=new BossJobReviewer.Filters(List.of(),List.of(),List.of(),15.0,25.0,Set.of(),Set.of(),Set.of(),false);
        for(String raw:List.of("","面议","\uE1005-25K","200-300元/天")) {
            // 包含完整 JD 仍不能用“描述不为空”代替已经补读详情。
            var job=new BossReviewJob("id","Agent开发","公司","广州",raw,"","","","","负责开发Agent后端服务。","","");
            assertNull(reviewer.preScreen(job,BossReviewPolicy.defaults(),filters));
            var result=reviewer.preScreen(job,BossReviewPolicy.defaults(),filters,true);
            assertFalse(result.approved());assertTrue(result.reason().contains("自动跳过"));
            assertFalse(result.reason().contains("人工"));
        }
        var encoded=new BossReviewJob("id","Agent开发","公司","广州","\uE032\uE036-\uE033\uE036K","","","","","","","");
        assertNull(reviewer.preScreen(encoded,BossReviewPolicy.defaults(),filters,true));
        var tooLow=encoded.withSalary("\uE032\uE031-\uE032\uE033K");
        assertEquals("reject",reviewer.preScreen(tooLow,BossReviewPolicy.defaults(),filters).decision());
        // 小数月薪边界不能因去掉小数点而变成 150K。
        assertEquals("reject",reviewer.preScreen(encoded.withSalary("14.5-14.9K"),BossReviewPolicy.defaults(),filters,true).decision());
        verifyNoInteractions(ai);
    }
}
