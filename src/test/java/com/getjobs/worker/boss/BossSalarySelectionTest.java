package com.getjobs.worker.boss;

import com.getjobs.application.service.BossService;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossSalarySelectionTest {
    private BossSalarySelection selection(String raw) {return BossSalarySelection.fromStored(raw,code->Map.of("402","3K以下","403","3-5K","404","5-10K","405","10-20K","406","20-50K","407","50K以上").get(code));}
    @Test void selectedTenToTwentyIncludesLowOverlapsAndRejectsSalariesAboveTwenty() {
        var selected=selection("[405]");assertEquals(selection("[10-20K]"),selected);
        assertEquals(10.0,selected.minimumK());assertEquals(20.0,selected.maximumK());
        assertTrue(selected.overlaps(9,12));assertTrue(selected.overlaps(10,14));assertTrue(selected.overlaps(20,30));
        assertFalse(selected.overlaps(21,25));assertFalse(selected.overlaps(25,35));assertFalse(selected.overlaps(5,9));
    }
    @Test void multipleSelectionsRetainGapsAndOpenEndedBounds() {
        var separated=selection("[403,406]");assertTrue(separated.overlaps(3,4));assertTrue(separated.overlaps(22,30));assertFalse(separated.overlaps(12,18));
        var open=selection("[3K以下,50K以上]");assertNull(open.minimumK());assertNull(open.maximumK());assertFalse(open.unrestricted());
        assertTrue(open.overlaps(1,2));assertTrue(open.overlaps(60,70));assertFalse(open.overlaps(10,20));
    }
    @Test void emptyAndUnlimitedSelectionDoNotRetainHiddenSalaryLimitsAndInvalidCodesFailClearly() {
        for(String raw:Arrays.asList(null,"","[]","[0]","[不限]","[0,405]")){assertTrue(selection(raw).unrestricted());assertTrue(selection(raw).overlaps(1,100));}
        assertThrows(IllegalArgumentException.class,()->selection("[999]"));assertThrows(IllegalArgumentException.class,()->selection("[坏数据]"));
    }
    @Test void ordinaryWorkerUsesTheSameSelectionEvenWhenLegacyValuesDisagree() throws Exception {
        var worker=new Boss(null,null,null,null,null,null);var config=new BossConfig();
        config.setExpectedSalary(List.of(15,25));config.setSalarySelection(selection("[10-20K]"));worker.setConfig(config);
        var check=Boss.class.getDeclaredMethod("isSalaryNotExpected",String.class);check.setAccessible(true);
        assertEquals(false,check.invoke(worker,"9-12K"));assertEquals(true,check.invoke(worker,"21-25K"));
        config.setSalarySelection(selection("[3K以下,50K以上]"));assertEquals(true,check.invoke(worker,"10-20K"));assertEquals(false,check.invoke(worker,"60-70K"));
        config.setSalarySelection(selection("[不限]"));assertEquals(false,check.invoke(worker,"面议"));
    }

    @Test void ordinarySubmissionChecksTheOpenedJobsSalaryBeforeLookingForTheChatButton() {
        var boss = new Boss(mock(BossService.class), null, null, null, null, null);
        var config = new BossConfig();
        config.setSalarySelection(new BossSalaryPolicy("custom", 12.0, 18.0, 12.0, 20.0).selection(null, code -> null));
        boss.setConfig(config);
        Page listing = mock(Page.class), detail = mock(Page.class);
        Locator empty = mock(Locator.class), salaries = mock(Locator.class);
        when(listing.getByText(any(java.util.regex.Pattern.class))).thenReturn(empty);
        when(detail.locator(contains(".job-banner .salary"))).thenReturn(salaries);
        var job = new Job();
        job.setJobName("示例研发岗位");
        job.setHref("https://www.zhipin.com/job_detail/salary-fixture.html");
        when(detail.url()).thenReturn(job.getHref());
        boss.setPage(listing);
        try (var pages = mockStatic(PlaywrightUtil.class); var navigators = mockConstruction(BossChatNavigator.class)) {
            pages.when(() -> PlaywrightUtil.newBackgroundPage(listing)).thenReturn(detail);
            var rejected = Map.of("15-60K", "最高月薪 60K", "8-15K", "最低月薪 8K", "面议", "薪资面议");
            for (var example : rejected.entrySet()) {
                job.setSalary("15-18K");
                when(salaries.allTextContents()).thenReturn(List.of(example.getKey()));
                assertEquals(false, ReflectionTestUtils.invokeMethod(boss, "resumeSubmission", "研发", job));
                assertEquals(example.getKey().equals("面议") ? "未投递" : "已过滤",
                        ReflectionTestUtils.getField(boss, "lastSubmissionStatus"));
                assertTrue(String.valueOf(ReflectionTestUtils.getField(boss, "lastSubmissionReason")).contains(example.getValue()));
            }
            verify(detail, never()).locator("a.btn-startchat, a.op-btn-chat");
        }
    }
}
