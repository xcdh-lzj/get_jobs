package com.getjobs.worker.boss;

import com.getjobs.application.service.BossService;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossDailyLimitTest {
    @Test void detectorRetriesNavigationButDoesNotSilentlyIgnoreUnexpectedBrowserErrors() {
        var page=mock(Page.class);
        var notices=mock(com.microsoft.playwright.Locator.class);
        when(page.getByText(any(java.util.regex.Pattern.class))).thenReturn(notices);
        when(notices.count()).thenThrow(new com.microsoft.playwright.PlaywrightException("Execution context was destroyed"));
        assertDoesNotThrow(() -> BossCommunicationLimit.check(page));
        var unexpected=new com.microsoft.playwright.PlaywrightException("Unsupported selector flags");
        doThrow(unexpected).when(notices).count();
        assertSame(unexpected,assertThrows(com.microsoft.playwright.PlaywrightException.class,() -> BossCommunicationLimit.check(page)));
    }

    @Test void quotaInsideKeywordIsNotSwallowedAndNoOtherKeywordsOrCitiesAreVisited() {
        var page=mock(Page.class);
        var boss=new Boss(mock(BossService.class),null,null,null,null,null);
        var config=new BossConfig();
        config.setCityCode(List.of("101280100","101020100"));
        config.setKeywords(List.of("agent开发","Java"));
        boss.setConfig(config); boss.setPage(page); boss.setShouldStopCallback(() -> false);
        Boss.ProgressCallback progress=mock(Boss.ProgressCallback.class);
        boss.setProgressCallback(progress);
        var navigated=new AtomicBoolean();
        when(page.navigate(anyString(),any(Page.NavigateOptions.class))).thenAnswer(call -> { navigated.set(true); return null; });
        try (var pauses=mockStatic(PlaywrightUtil.class); var notices=mockStatic(BossCommunicationLimit.class)) {
            notices.when(() -> BossCommunicationLimit.check(page)).thenAnswer(call -> {
                if (navigated.get()) throw new BossDailyLimitReachedException("您已达到沟通上限");
                return null;
            });
            assertThrows(BossDailyLimitReachedException.class,boss::execute);
        }
        verify(page,times(1)).navigate(anyString(),any(Page.NavigateOptions.class));
        verify(progress,times(1)).searchStarted(anyString(),eq("agent开发"));
        verify(progress,never()).searchStarted(anyString(),eq("Java"));
        assertTrue(boss.getResultList().isEmpty());
    }
}
