package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossJobBlockTest {
    static final String BODY = "您的工作经历与该岗位要求不匹配，得到回复的概率较低，建议您选择其他职位沟通。";

    @Test void matchesTheKnownNoticeButNotUnrelatedRequirementsOrDailyQuota() {
        assertTrue(BossJobBlock.matches("工作经历不匹配", BODY));
        assertTrue(BossJobBlock.matches(" 工作经历不匹配 ", BODY.replace("，", ",\n").replace("。", "！")));
        for (String title : new String[]{null, "工作经验要求", "您已达到沟通上限", "学历不匹配"}) {
            assertFalse(BossJobBlock.matches(title, BODY));
        }
        assertFalse(BossJobBlock.matches("工作经历不匹配", "建议修改个人资料"));
        assertFalse(BossJobBlock.matches("工作经历不匹配", BODY + "购买服务后继续"));
    }

    @Test void onlyVisibleModalTitleAndBodyAreAJobBlockAndNoButtonsAreClicked() {
        Page page = mock(Page.class);
        Locator titles = mock(Locator.class), title = mock(Locator.class), modal = mock(Locator.class);
        Locator messages = mock(Locator.class), message = mock(Locator.class);
        when(page.getByText(BossJobBlock.TITLE)).thenReturn(titles);
        when(titles.count()).thenReturn(1); when(titles.nth(0)).thenReturn(title);
        when(title.isVisible()).thenReturn(true); when(title.textContent()).thenReturn("工作经历不匹配");
        when(title.locator(BossJobBlock.MODAL)).thenReturn(modal);
        when(modal.count()).thenReturn(1); when(modal.isVisible()).thenReturn(true);
        when(modal.getByText(BossJobBlock.BODY)).thenReturn(messages);
        when(messages.count()).thenReturn(1); when(messages.nth(0)).thenReturn(message);
        when(message.isVisible()).thenReturn(true); when(message.textContent()).thenReturn(BODY);
        assertThrows(BossJobBlockedException.class, () -> BossJobBlock.check(page));
        when(title.isVisible()).thenReturn(false);
        assertDoesNotThrow(() -> BossJobBlock.check(page));
        when(title.isVisible()).thenReturn(true); when(modal.count()).thenReturn(0);
        assertDoesNotThrow(() -> BossJobBlock.check(page)); // Same words in the JD are not a modal.
        when(modal.count()).thenReturn(1); when(message.isVisible()).thenReturn(false);
        assertDoesNotThrow(() -> BossJobBlock.check(page));
        verify(title, never()).click(); verify(modal, never()).click(); verify(message, never()).click();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsInlineAndNewWindowBlocksBeforeAcceptingAnUnderlyingInput(boolean popup) {
        Page origin = mock(Page.class), child = mock(Page.class), unrelated = mock(Page.class);
        Locator empty = mock(Locator.class), input = mock(Locator.class), button = mock(Locator.class);
        when(origin.getByText(any(java.util.regex.Pattern.class))).thenReturn(empty);
        when(child.getByText(any(java.util.regex.Pattern.class))).thenReturn(empty);
        when(origin.locator(BossChatNavigator.INPUT)).thenReturn(input); when(input.first()).thenReturn(input);
        when(origin.url()).thenReturn("http://getjobs.test/job");
        var clicked = new AtomicBoolean(); var listener = new AtomicReference<Consumer<Page>>();
        doAnswer(call -> { listener.set(call.getArgument(0)); return null; }).when(origin).onPopup(any());
        doAnswer(call -> { clicked.set(true); if (popup) listener.get().accept(child); return null; }).when(button).click(any(Locator.ClickOptions.class));
        try (var blocks = mockStatic(BossJobBlock.class); var navigator = new BossChatNavigator(origin)) {
            blocks.when(() -> BossJobBlock.check(popup ? child : origin)).thenAnswer(call -> {
                if (clicked.get()) throw new BossJobBlockedException("平台工作经历不匹配");
                return null;
            });
            assertThrows(BossJobBlockedException.class, () -> navigator.open(button, false, false, null, 1000));
            verify(button, times(1)).click(any(Locator.ClickOptions.class));
            verify(input, never()).fill(anyString());
        }
        verify(origin).close(); if (popup) verify(child).close();
        verify(unrelated, never()).close();
    }

    @Test void checksLateNoticesBeforeSendingAndRespectsStopBeforeAnyNewAction() {
        Page page = mock(Page.class); Locator empty = mock(Locator.class);
        when(page.getByText(any(java.util.regex.Pattern.class))).thenReturn(empty);
        try (var blocks = mockStatic(BossJobBlock.class); var navigator = new BossChatNavigator(page)) {
            blocks.when(() -> BossJobBlock.check(page)).thenThrow(new BossJobBlockedException("平台工作经历不匹配"));
            assertThrows(BossJobBlockedException.class, () -> navigator.prepareToSend(null, 1000));
            blocks.clearInvocations();
            assertFalse(navigator.prepareToSend(() -> true, 1000));
            blocks.verifyNoInteractions();
        }
    }

    @Test void dailyQuotaRemainsAnAccountLevelConditionWithPriorityOverJobRejection() {
        Page page = mock(Page.class);
        try (var limits = mockStatic(BossCommunicationLimit.class); var blocks = mockStatic(BossJobBlock.class);
             var navigator = new BossChatNavigator(page)) {
            limits.when(() -> BossCommunicationLimit.check(page)).thenThrow(new BossDailyLimitReachedException("今日已达上限"));
            assertThrows(BossDailyLimitReachedException.class, navigator::checkBlockingNotices);
            blocks.verifyNoInteractions();
        }
    }
}
