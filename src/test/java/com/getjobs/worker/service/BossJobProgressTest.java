package com.getjobs.worker.service;

import com.getjobs.application.service.*;
import com.getjobs.worker.manager.BossBrowserSessions;
import java.util.List;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossJobProgressTest {
    private final PlaywrightManager manager = mock(PlaywrightManager.class);
    private final ConfigService configs = mock(ConfigService.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<Boss> provider = mock(ObjectProvider.class);
    private final Boss boss = mock(Boss.class);
    private final AtomicReference<Boss.ProgressCallback> callback = new AtomicReference<>();
    private final BossAccountService accounts = mock(BossAccountService.class);
    private final BossBrowserSessions browsers = mock(BossBrowserSessions.class);
    private final BossJobService service = new BossJobService(manager, configs, provider, accounts, browsers, new BossAccountLocks());

    @BeforeEach void setup() {
        when(browsers.loggedIn(1L)).thenReturn(true);
        when(accounts.require(1L)).thenReturn(new BossAccountService.Account(1,"默认账号",true));
        when(accounts.profile(1L)).thenReturn(new BossAccountService.Profile("个人介绍","模板"));
        when(browsers.ready(1L,false)).thenReturn(mock(Page.class));
        var config = new BossConfig(); config.setKeywords(List.of("Java")); config.setCityCode(List.of("上海"));
        when(configs.getBossConfig()).thenReturn(config);
        when(provider.getObject()).thenReturn(boss);
        doAnswer(call -> { callback.set(call.getArgument(0)); return null; }).when(boss).setProgressCallback(any());
        when(manager.callOnPlaywright(any())).thenAnswer(call -> ((Callable<?>) call.getArgument(0)).call());
    }

    private BossProgressTracker.Snapshot snapshot() {
        return (BossProgressTracker.Snapshot) service.getStatus().get("progress");
    }

    @Test void publishesActualCountersAndTerminalStateForRefresh() {
        when(boss.execute()).thenAnswer(ignored -> {
            callback.get().searchStarted("上海", "Java");
            callback.get().jobStarted("开发工程师", "示例公司", 2);
            callback.get().accept("AI 正在分析", null, null);
            callback.get().jobProcessed(true, 1, 2);
            callback.get().jobProcessed(false, 2, 2);
            return 1;
        });
        var messages = new ArrayList<JobProgressMessage>();
        var statesAtDelivery = new ArrayList<String>();
        service.executeDelivery(message -> { messages.add(message); statesAtDelivery.add(snapshot().state()); });
        assertEquals("completed", snapshot().state());
        assertEquals("completed", statesAtDelivery.getLast());
        assertEquals(2, snapshot().processedCount());
        assertEquals(1, snapshot().deliveredCount());
        assertEquals(1, snapshot().skippedCount());
        assertEquals("开发工程师", snapshot().jobName());
        assertFalse(service.isRunning());
        assertTrue(messages.stream().anyMatch(m -> "success".equals(m.getType())));
        verify(manager).resumeBossMonitoring();
    }

    @Test void failedLoginIsVisibleAndDoesNotStartBrowserDelivery() {
        when(browsers.loggedIn(1L)).thenReturn(false);
        service.executeDelivery(ignored -> {});
        assertEquals("failed", snapshot().state());
        assertFalse(service.isRunning());
        assertTrue(snapshot().message().contains("登录已失效"));
        verify(boss, never()).execute();
    }

    @Test void singleAccountDailyLimitEndsWithExplicitStatusAndReleasesExecutionSlot() {
        when(boss.execute()).thenThrow(new com.getjobs.worker.boss.BossDailyLimitReachedException("您已达到沟通上限"));
        service.executeDelivery(ignored -> {});
        assertEquals("quota_reached",snapshot().state());
        assertEquals(0,snapshot().deliveredCount());
        assertFalse(service.isRunning());
        assertTrue(snapshot().message().contains("今日沟通已达上限"));
        assertTrue(service.getQueue().message().contains("当前账号今日沟通已达上限"));
    }

    @Test void stopRequestKeepsTaskRunningUntilWorkerAcknowledgesIt() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        when(boss.execute()).thenAnswer(ignored -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return 0;
        });
        try {
            assertTrue(service.startDelivery(message -> {
                if (!service.isRunning()) finished.countDown();
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.stopDelivery();
            assertTrue(service.isRunning());
            assertEquals("stopping", snapshot().state());
            assertFalse(service.startDelivery(ignored -> {}));
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals("stopped", snapshot().state());
            assertFalse(service.isRunning());
        } finally { release.countDown(); }
    }
}
