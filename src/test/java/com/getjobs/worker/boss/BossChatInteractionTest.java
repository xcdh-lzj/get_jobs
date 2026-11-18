package com.getjobs.worker.boss;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossChatInteractionTest {
    Page page;Locator input,send,empty;
    @BeforeEach void setup() {
        page=mock(Page.class);input=mock(Locator.class);send=mock(Locator.class);empty=mock(Locator.class);
        when(page.getByText(any(java.util.regex.Pattern.class))).thenReturn(empty);
    }
    @Test void clearInputAndSendUseBoundedNormalActions() {
        try(var overlays=mockStatic(BossChatOverlay.class,CALLS_REAL_METHODS);var navigator=new BossChatNavigator(page)) {
            assertTrue(navigator.fillMessage(input,"合成消息",null,2000));
            assertTrue(navigator.sendMessage(send,null,2000));
        }
        verify(input).click(argThat(options->options.timeout==2000));
        verify(input).fill(eq("合成消息"),argThat(options->options.timeout==2000 && !Boolean.TRUE.equals(options.force)));
        verify(send,times(1)).click(argThat(options->options.timeout==2000 && !Boolean.TRUE.equals(options.force)));
    }
    @Test void persistentUnknownDialogNeverReceivesAnInputOrSendClickAndDoesNotWaitThirtySeconds() {
        try(var overlays=mockStatic(BossChatOverlay.class,CALLS_REAL_METHODS);var navigator=new BossChatNavigator(page)) {
            overlays.when(()->BossChatOverlay.find(input)).thenReturn(new BossChatOverlay.Notice("合成未知提示","请选择一种操作"));
            var error=assertTimeout(Duration.ofSeconds(2),()->assertThrows(BossChatInteractionException.class,
                    ()->navigator.fillMessage(input,"合成消息",null,30)));
            assertTrue(error.getMessage().contains("合成未知提示"));
            verify(input,never()).click(any(Locator.ClickOptions.class));verify(input,never()).fill(anyString(),any(Locator.FillOptions.class));
        }
    }
    @Test void shortLivedOverlayCanDisappearWithoutAnyConfirmationClick() {
        try(var overlays=mockStatic(BossChatOverlay.class,CALLS_REAL_METHODS);var navigator=new BossChatNavigator(page)) {
            overlays.when(()->BossChatOverlay.find(input)).thenReturn(new BossChatOverlay.Notice("提示","正在消失"),null,null);
            assertTrue(navigator.fillMessage(input,"合成消息",null,1000));verify(input,times(1)).click(any(Locator.ClickOptions.class));
        }
    }
    @Test void dialogAppearingDuringClickIsConvertedToJobScopedFailure() {
        doThrow(new TimeoutError("boss-layer__wrapper intercepts pointer events")).when(input).click(any(Locator.ClickOptions.class));
        try(var overlays=mockStatic(BossChatOverlay.class,CALLS_REAL_METHODS);var navigator=new BossChatNavigator(page)) {
            overlays.when(()->BossChatOverlay.find(input)).thenReturn(null,new BossChatOverlay.Notice("合成晚到弹窗","输入框被遮挡"));
            var error=assertThrows(BossChatInteractionException.class,()->navigator.fillMessage(input,"合成消息",null,2000));
            assertInstanceOf(TimeoutError.class,error.getCause());assertTrue(error.getMessage().contains("合成晚到弹窗"));
            verify(input,times(1)).click(any(Locator.ClickOptions.class));verify(input,never()).fill(anyString(),any(Locator.FillOptions.class));
        }
    }
    @Test void sendTimeoutIsNeverRetried() {
        doThrow(new TimeoutError("send click did not return")).when(send).click(any(Locator.ClickOptions.class));
        try(var overlays=mockStatic(BossChatOverlay.class,CALLS_REAL_METHODS);var navigator=new BossChatNavigator(page)) {
            assertThrows(BossChatInteractionException.class,()->navigator.sendMessage(send,null,2000));
            verify(send,times(1)).click(any(Locator.ClickOptions.class));
        }
    }
    @Test void lateDailyLimitKeepsItsAccountLevelMeaning() {
        var clicked=new AtomicBoolean();
        doAnswer(call->{clicked.set(true);throw new TimeoutError("late modal");}).when(input).click(any(Locator.ClickOptions.class));
        try(var overlays=mockStatic(BossChatOverlay.class,CALLS_REAL_METHODS);var limits=mockStatic(BossCommunicationLimit.class);var navigator=new BossChatNavigator(page)) {
            limits.when(()->BossCommunicationLimit.check(page)).thenAnswer(call->{if(clicked.get())throw new BossDailyLimitReachedException("今日沟通已达上限");return null;});
            assertThrows(BossDailyLimitReachedException.class,()->navigator.fillMessage(input,"合成消息",null,2000));
            verify(input,never()).fill(anyString(),any(Locator.FillOptions.class));
        }
    }
    @Test void verificationStopsTheTaskAndStopRequestPreventsAnyNewInteraction() {
        try(var overlays=mockStatic(BossChatOverlay.class,CALLS_REAL_METHODS);var navigator=new BossChatNavigator(page)) {
            assertFalse(navigator.fillMessage(input,"合成消息",()->true,2000));overlays.verifyNoInteractions();
            overlays.when(()->BossChatOverlay.find(input)).thenReturn(new BossChatOverlay.Notice("安全验证","请完成验证后继续"));
            assertThrows(IllegalStateException.class,()->navigator.fillMessage(input,"合成消息",null,2000));
            verify(input,never()).click(any(Locator.ClickOptions.class));
        }
    }
    @Test void blockerSummaryRedactsContactDetailsAndIsBounded() {
        String phone="138"+"0".repeat(8); // 运行时生成合成测试数据，源码不保存电话号码。
        String summary=new BossChatOverlay.Notice("提示","请联系 "+phone+" 或 fixture@example.invalid "+"长".repeat(300)).summary();
        assertFalse(summary.contains(phone));assertFalse(summary.contains("fixture@example.invalid"));assertTrue(summary.length()<=181);
    }
}
