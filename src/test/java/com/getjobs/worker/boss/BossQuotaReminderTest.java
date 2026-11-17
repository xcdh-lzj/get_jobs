package com.getjobs.worker.boss;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BossQuotaReminderTest {
    @Test void readsActualScreenshotAndAlternateCountsWithoutAssumingADailyLimitOf150() {
        for(String text:List.of("您今天已与120位BOSS沟通，还剩30次沟通机会哦","您今天已与１２０位BOSS沟通，还剩３０次沟通机会哦","你今日已与 120 位 boss 沟通, 还有 30 次沟通机会！"))
            assertEquals(new BossQuotaReminder.Counts(120,30),BossQuotaReminder.parse(text));
        assertEquals(new BossQuotaReminder.Counts(50,50),BossQuotaReminder.parse("您今天已与50位BOSS沟通，还剩50次沟通机会哦"));
        assertEquals(0,BossQuotaReminder.parse("您今天已与150位BOSS沟通，还剩0次沟通机会哦").remaining());
    }
    @Test void unknownOrAdditionalInstructionsAreNotInformationalQuotaNotices() {
        for(String text:Arrays.asList(null,"","您已达到沟通上限","您今天已与120位BOSS沟通，还剩-1次沟通机会哦","您今天已与120位BOSS沟通，还剩30次沟通机会哦，请购买会员","请完成安全验证后继续沟通"))
            assertNull(BossQuotaReminder.parse(text),text);
    }
}
