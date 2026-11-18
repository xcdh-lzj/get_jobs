package com.getjobs.worker.boss;

import com.getjobs.application.service.BossService;
import com.getjobs.worker.utils.*;
import com.microsoft.playwright.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossSubmissionTimeoutTest {
    @ParameterizedTest @ValueSource(strings={"input","send","navigation"})
    void oneUnavailableChatDoesNotAbortNextJobOrCountTheFailedJobAsDelivered(String stage) throws Exception {
        var service=mock(BossService.class);var boss=new Boss(service,null,null,null,null,null);
        var config=new BossConfig();config.setSayHi("合成消息");boss.setConfig(config);
        Page listing=mock(Page.class),detail=mock(Page.class);
        Locator empty=mock(Locator.class),entry=mock(Locator.class),input=mock(Locator.class),send=mock(Locator.class);
        when(listing.getByText(any(java.util.regex.Pattern.class))).thenReturn(empty);
        when(detail.locator("a.btn-startchat, a.op-btn-chat")).thenReturn(entry);
        when(entry.count()).thenReturn(1);when(entry.first()).thenReturn(entry);when(entry.textContent()).thenReturn("立即沟通");
        when(input.first()).thenReturn(input);
        when(detail.locator("div.send-message, button[type='send'].btn-send, button.btn-send")).thenReturn(send);
        when(send.count()).thenReturn(1);when(send.first()).thenReturn(send);
        boss.setPage(listing);var messages=new ArrayList<String>();boss.setProgressCallback((text,current,total)->messages.add(text));
        var method=Boss.class.getDeclaredMethod("resumeSubmission",String.class,Job.class);method.setAccessible(true);
        var first=new Job();first.setJobName("受遮挡的合成岗位");first.setHref("https://www.zhipin.com/job_detail/synthetic-first.html");
        var next=new Job();next.setJobName("后续合成岗位");next.setHref("https://www.zhipin.com/job_detail/synthetic-next.html");
        if(stage.equals("navigation"))when(detail.navigate(anyString())).thenThrow(new TimeoutError("detail loading timeout")).thenReturn(null);
        try(var sleeps=mockStatic(PlaywrightUtil.class);var navigators=mockConstruction(BossChatNavigator.class,(navigator,context)->{
            when(navigator.open(any(),anyBoolean(),anyBoolean(),any(),anyLong())).thenReturn(new BossChatNavigator.Chat(detail,input,false));
            when(navigator.prepareToSend(any(),anyLong())).thenReturn(true);
            when(navigator.fillMessage(any(),anyString(),any(),anyLong())).thenReturn(true);
            when(navigator.sendMessage(any(),any(),anyLong())).thenReturn(true);
            if(context.getCount()==1) {
                var blocked=new BossChatInteractionException("合成弹窗遮挡输入框",new TimeoutError("intercepts pointer events"));
                if(stage.equals("input"))when(navigator.fillMessage(any(),anyString(),any(),anyLong())).thenThrow(blocked);
                if(stage.equals("send"))when(navigator.sendMessage(any(),any(),anyLong())).thenThrow(blocked);
            }
        })) {
            sleeps.when(()->PlaywrightUtil.newBackgroundPage(listing)).thenReturn(detail);
            assertEquals(false,method.invoke(boss,"合成方向",first));assertTrue(boss.getResultList().isEmpty());
            assertEquals(BossCityYieldMonitor.Outcome.UNCERTAIN,org.springframework.test.util.ReflectionTestUtils.getField(boss,"lastSubmissionOutcome"));
            assertTrue(messages.stream().anyMatch(text->text.contains("继续后续岗位")));verify(navigators.constructed().getFirst()).close();
            assertEquals(true,method.invoke(boss,"合成方向",next));assertEquals(List.of(next),boss.getResultList());
        }
        verify(listing,never()).close();
    }
}
