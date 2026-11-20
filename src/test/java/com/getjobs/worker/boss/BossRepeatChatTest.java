package com.getjobs.worker.boss;

import com.getjobs.application.service.BossService;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossRepeatChatTest {
    @ParameterizedTest
    @CsvSource({
            "立即沟通,false,true,false,false", "立即沟通,true,true,false,false",
            "继续沟通,false,false,false,false", "继续沟通,true,false,false,false",
            "立即沟通,,true,false,false", "继续沟通,,false,false,false",
            "已关闭,true,false,false,false", "继续沟通,true,false,true,false",
            "立即沟通,true,true,true,false", "立即沟通,true,false,false,true"
    })
    void searchDeliverySendsOnlyInNewConversations(String text, Boolean enabled, boolean expected, boolean emptyMessages,
                                                  boolean openedExisting) throws Exception {
        BossService service = mock(BossService.class);
        Boss boss = new Boss(service, null, null, null, null, null);
        BossConfig config = new BossConfig();
        config.setAllowRepeatChat(enabled);
        config.setSayHi("您好，希望进一步沟通。");
        config.setRepeatChatMessages(emptyMessages ? " \n\t" : "\n  自定义跟进消息  \n\n");
        config.setEnableAI(true);
        boss.setConfig(config);
        Page listing = mock(Page.class);
        Page detail = mock(Page.class);
        Locator more = mock(Locator.class);
        Locator button = mock(Locator.class);
        Locator input = mock(Locator.class);
        Locator send = mock(Locator.class);
        Locator quotaNotice = mock(Locator.class);
        when(listing.getByText(any(java.util.regex.Pattern.class))).thenReturn(quotaNotice);
        when(detail.getByText(any(java.util.regex.Pattern.class))).thenReturn(quotaNotice);
        when(listing.locator(anyString())).thenReturn(more);
        when(more.count()).thenReturn(1);
        when(more.first()).thenReturn(more);
        when(more.getAttribute("href")).thenReturn("/job_detail/test.html");
        when(detail.locator("a.btn-startchat, a.op-btn-chat")).thenReturn(button);
        when(button.count()).thenReturn(1);
        when(button.first()).thenReturn(button);
        when(button.textContent()).thenReturn(text);
        when(detail.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area")).thenReturn(input);
        when(input.count()).thenReturn(1);
        when(input.first()).thenReturn(input);
        when(input.isVisible()).thenReturn(true);
        when(input.evaluate(anyString())).thenReturn("textarea");
        when(detail.locator("div.send-message, button[type='send'].btn-send, button.btn-send")).thenReturn(send);
        when(send.count()).thenReturn(1);
        when(send.first()).thenReturn(send);
        boss.setPage(listing);
        var submission = Boss.class.getDeclaredMethod("resumeSubmission", String.class, Job.class);
        submission.setAccessible(true);
        Job job = new Job();
        if ("继续沟通".equals(text)) job.setJobInfo("已有会话不会调用AI重新分析此JD");
        try (var ignored = mockStatic(PlaywrightUtil.class);
             var navigators = mockConstruction(BossChatNavigator.class, (navigator, construction) -> {
                 when(navigator.prepareToSend(any(),anyLong())).thenReturn(true);
                 when(navigator.fillMessage(any(),anyString(),any(),anyLong())).thenAnswer(call->{input.fill(call.getArgument(1));return true;});
                 when(navigator.sendMessage(any(),any(),anyLong())).thenAnswer(call->{send.click();return true;});
                 when(navigator.open(any(), anyBoolean(), anyBoolean(), any(), anyLong())).thenAnswer(invocation -> {
                     button.click();
                     return new BossChatNavigator.Chat(detail, input, openedExisting);
                 });
             })) {
            ignored.when(() -> PlaywrightUtil.newBackgroundPage(listing)).thenReturn(detail);
            assertEquals(expected, submission.invoke(boss, "Java", job));
            if ("立即沟通".equals(text)) {
                verify(navigators.constructed().getFirst()).open(eq(button), eq(false), eq(false), any(), anyLong());
            }
        }
        verify(button, times("立即沟通".equals(text) ? 1 : 0)).click();
        verify(input, times(expected ? 1 : 0)).fill(config.getSayHi());
        verify(send, times(expected ? 1 : 0)).click();
    }

    @Test
    void selectsOnlyNonBlankLinesAndKeepsUserText() {
        BossConfig config = new BossConfig();
        config.setRepeatChatMessages("\r\n 第一条 \r\n\t\r\n第二条？\r\n");
        for (int i = 0; i < 100; i++) {
            assertTrue(java.util.Set.of("第一条", "第二条？").contains(config.randomRepeatChatMessage()));
        }
        config.setRepeatChatMessages(" \n\t\r\n");
        assertNull(config.randomRepeatChatMessage());
        config.setRepeatChatMessages(null);
        assertNull(config.randomRepeatChatMessage());
    }

    @Test
    void includesSixDefaultFollowUpMessages() {
        BossConfig config = new BossConfig();
        assertEquals(6, config.getRepeatChatMessages().lines().count());
        assertTrue(config.getRepeatChatMessages().contains("请问贵公司还招人吗？"));
    }
}
