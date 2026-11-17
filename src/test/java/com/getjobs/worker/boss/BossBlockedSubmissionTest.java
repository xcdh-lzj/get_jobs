package com.getjobs.worker.boss;

import com.getjobs.application.service.BossService;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossBlockedSubmissionTest {
    @ParameterizedTest @ValueSource(strings = {"open", "prepare", "after-send"})
    void blockedJobIsNotCountedAndNextJobCanStillComplete(String stage) throws Exception {
        BossService service = mock(BossService.class);
        Boss boss = new Boss(service, null, null, null, null, null);
        BossConfig config = new BossConfig(); config.setSayHi("合成测试招呼语"); boss.setConfig(config);
        var messages = new ArrayList<String>(); boss.setProgressCallback((text, current, total) -> messages.add(text));
        Page listing = mock(Page.class), detail = mock(Page.class);
        Locator empty = mock(Locator.class), button = mock(Locator.class), input = mock(Locator.class), send = mock(Locator.class);
        when(listing.getByText(any(java.util.regex.Pattern.class))).thenReturn(empty);
        when(detail.locator("a.btn-startchat, a.op-btn-chat")).thenReturn(button);
        when(button.count()).thenReturn(1); when(button.first()).thenReturn(button); when(button.textContent()).thenReturn("立即沟通");
        when(input.first()).thenReturn(input); when(input.evaluate(anyString())).thenReturn("textarea");
        when(detail.locator("div.send-message, button[type='send'].btn-send, button.btn-send")).thenReturn(send);
        when(send.count()).thenReturn(1); when(send.first()).thenReturn(send);
        boss.setPage(listing);
        var method = Boss.class.getDeclaredMethod("resumeSubmission", String.class, Job.class); method.setAccessible(true);
        Job first = new Job(); first.setJobName("合成受阻岗位"); first.setHref("https://www.zhipin.com/job_detail/fixture-first.html");
        Job next = new Job(); next.setJobName("合成后续岗位"); next.setHref("https://www.zhipin.com/job_detail/fixture-next.html");
        try (var sleeps = mockStatic(PlaywrightUtil.class); var navigators = mockConstruction(BossChatNavigator.class, (navigator, context) -> {
            when(navigator.open(any(), anyBoolean(), anyBoolean(), any(), anyLong())).thenReturn(new BossChatNavigator.Chat(detail, input, false));
            when(navigator.prepareToSend(any(), anyLong())).thenReturn(true);
            if (context.getCount() == 1) {
                var blocked = new BossJobBlockedException("Boss 提示工作经历与该岗位要求不匹配");
                if (stage.equals("open")) when(navigator.open(any(), anyBoolean(), anyBoolean(), any(), anyLong())).thenThrow(blocked);
                if (stage.equals("prepare")) when(navigator.prepareToSend(any(), anyLong())).thenThrow(blocked);
                if (stage.equals("after-send")) doNothing().doThrow(blocked).when(navigator).checkBlockingNotices();
            }
        })) {
            sleeps.when(() -> PlaywrightUtil.newBackgroundPage(listing)).thenReturn(detail);
            assertEquals(false, method.invoke(boss, "合成方向", first));
            assertTrue(boss.getResultList().isEmpty());
            assertEquals(BossCityYieldMonitor.Outcome.UNAVAILABLE,
                    org.springframework.test.util.ReflectionTestUtils.getField(boss, "lastSubmissionOutcome"));
            assertTrue(messages.stream().anyMatch(text -> text.startsWith("自动跳过（未投递）") && text.contains("工作经历")));
            verify(navigators.constructed().getFirst()).close();
            if (!stage.equals("after-send")) verify(send, never()).click();
            assertEquals(true, method.invoke(boss, "合成方向", next));
            assertEquals(java.util.List.of(next), boss.getResultList());
        }
        verify(service, never()).updateDeliveryStatus(anyString(), anyString(), eq("已投递"));
    }
}
