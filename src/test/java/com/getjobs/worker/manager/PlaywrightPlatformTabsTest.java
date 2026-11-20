package com.getjobs.worker.manager;

import com.getjobs.application.service.CookieService;
import com.getjobs.application.service.BossAccountService;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PlaywrightPlatformTabsTest {
    @Test void bossPageAndRequestedPlatformAreCreatedIndependently() {
        var manager = new PlaywrightManager();
        var context = mock(BrowserContext.class);
        var boss = mock(Page.class);
        var liepin = mock(Page.class);
        when(context.newPage()).thenReturn(boss, liepin);
        when(context.cookies("https://www.zhipin.com")).thenReturn(List.of(new Cookie("bst", "fixture")));
        ReflectionTestUtils.setField(manager, "context", context);
        var cookies = mock(CookieService.class);
        ReflectionTestUtils.setField(manager, "cookieService", cookies);

        manager.init();
        verify(context).newPage();
        verify(boss).navigate(eq("https://www.zhipin.com/web/geek/jobs"), any(Page.NavigateOptions.class));
        assertNull(manager.getLiepinPage());
        assertNull(manager.getJob51Page());
        assertNull(manager.getZhilianPage());
        manager.saveCookiesToDb("liepin", "manual save");
        verify(cookies, never()).saveOrUpdateCookie(eq("liepin"), any(), any());

        assertSame(liepin, manager.readyPlatform("liepin", true));
        assertSame(liepin, manager.readyPlatform("liepin", false));
        verify(context, times(2)).newPage();
        verify(liepin).navigate(eq("https://www.liepin.com"), any(Page.NavigateOptions.class));
        verify(liepin).bringToFront();
        verify(boss, never()).close();
        assertNull(manager.getJob51Page());
        assertNull(manager.getZhilianPage());
    }

    @Test void openingAnExtraBossAccountDoesNotOpenDefaultBrowser() {
        var manager = mock(PlaywrightManager.class);
        var context = mock(BrowserContext.class);
        var page = mock(Page.class);
        when(manager.callOnPlaywright(any())).thenAnswer(call -> call.<Callable<?>>getArgument(0).call());
        when(manager.newBossAccountContext(any(), eq(false))).thenReturn(context);
        when(context.pages()).thenReturn(List.of(page));
        var sessions = new BossBrowserSessions(manager, mock(BossAccountService.class), mock(CookieService.class));

        assertSame(page, sessions.ready(2, false));
        assertTrue(sessions.isOpen(2));
        verify(manager, never()).ensureReady();
        verify(page, never()).bringToFront();
    }
}
