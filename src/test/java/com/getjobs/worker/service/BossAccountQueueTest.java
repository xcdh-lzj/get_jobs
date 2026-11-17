package com.getjobs.worker.service;

import com.getjobs.application.service.*;
import com.getjobs.worker.boss.*;
import com.getjobs.worker.manager.*;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossAccountQueueTest {
    private final PlaywrightManager manager=mock(PlaywrightManager.class);
    private final ConfigService configs=mock(ConfigService.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<Boss> provider=mock(ObjectProvider.class);
    private final BossAccountService accounts=mock(BossAccountService.class);
    private final BossBrowserSessions browsers=mock(BossBrowserSessions.class);
    private final BossAccountLocks locks=new BossAccountLocks();
    private final BossJobService jobs=new BossJobService(manager,configs,provider,accounts,browsers,locks);
    private final Map<Long,Boss> workers=new HashMap<>();
    private final List<Long> execution=new CopyOnWriteArrayList<>();

    @BeforeEach void setup() {
        when(accounts.require(anyLong())).thenAnswer(c -> new BossAccountService.Account(c.getArgument(0),"账号"+c.getArgument(0),false));
        when(accounts.profile(anyLong())).thenAnswer(c -> new BossAccountService.Profile("介绍"+c.getArgument(0),"模板"));
        when(browsers.loggedIn(anyLong())).thenReturn(true);
        when(browsers.ready(anyLong(),eq(false))).thenReturn(mock(Page.class));
        var config=new BossConfig(); config.setKeywords(List.of("Java")); config.setCityCode(List.of("上海"));
        when(configs.getBossConfig()).thenReturn(config);
        when(manager.callOnPlaywright(any())).thenAnswer(c -> {
            // 模拟真正切换线程：账号范围必须显式传入，不能依赖请求线程。
            try (var executor=Executors.newSingleThreadExecutor()) {
                try { return executor.submit((Callable<?>)c.getArgument(0)).get(5,TimeUnit.SECONDS); }
                catch (ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException runtime) throw runtime;
                    throw e;
                }
            }
        });
        when(provider.getObject()).thenAnswer(ignored -> {
            long id=BossAccountContext.id();
            Boss worker=mock(Boss.class);
            when(worker.execute()).thenAnswer(c -> { execution.add(BossAccountContext.id()); return (int)id; });
            workers.put(id,worker);
            return worker;
        });
    }
    private CountDownLatch finished() { return new CountDownLatch(1); }
    private void start(List<Long> ids,CountDownLatch done) {
        assertTrue(jobs.startQueue(ids,m -> { if (!jobs.isRunning()) done.countDown(); }));
    }
    @Test void completesAccountsInChosenOrderWithSeparateProgressAndProfiles() throws Exception {
        var done=finished(); start(List.of(2L,1L),done);
        assertTrue(done.await(5,TimeUnit.SECONDS));
        assertEquals(List.of(2L,1L),execution);
        assertEquals("completed",jobs.getQueue().state());
        assertEquals(List.of(2,1),jobs.getQueue().items().stream().map(BossJobService.QueueItem::deliveredCount).toList());
        var a=(BossProgressTracker.Snapshot)jobs.getStatus(1).get("progress");
        var b=(BossProgressTracker.Snapshot)jobs.getStatus(2).get("progress");
        assertNotEquals(a.taskId(),b.taskId()); assertEquals(1,a.deliveredCount()); assertEquals(2,b.deliveredCount());
        verify(workers.get(2L)).setAiConfig(argThat(ai -> "介绍2".equals(ai.getIntroduce())));
        assertEquals(1L,BossAccountContext.id()); assertFalse(locks.busy(1));
    }
    @Test void stopWaitsForCurrentWorkerAndNeverStartsNextAccount() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var done=finished();
        when(provider.getObject()).thenAnswer(ignored -> {
            var worker=mock(Boss.class);
            when(worker.execute()).thenAnswer(c -> { entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); return 0; });
            return worker;
        });
        try {
            start(List.of(1L,2L),done); assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> locks.requireEditable(2));
            jobs.stopDelivery(); assertTrue(jobs.isRunning()); assertEquals("stopping",jobs.getQueue().state());
            assertFalse(jobs.startDelivery(2,ignored -> {}));
            release.countDown(); assertTrue(done.await(5,TimeUnit.SECONDS));
            verify(browsers,never()).ready(2,false);
            assertEquals("cancelled",jobs.getQueue().items().get(1).state());
            assertFalse(locks.busy(2));
        } finally { release.countDown(); }
    }
    @Test void loginFailureStopsQueueWithoutTouchingFollowingAccounts() throws Exception {
        when(browsers.loggedIn(1)).thenReturn(false);
        var done=finished(); start(List.of(1L,2L),done); assertTrue(done.await(5,TimeUnit.SECONDS));
        assertEquals("failed",jobs.getQueue().state());
        verify(browsers,never()).ready(2,false); verify(provider,never()).getObject();
        assertEquals("idle",((BossProgressTracker.Snapshot)jobs.getStatus(2).get("progress")).state());
    }
    @Test void stopImmediatelyAfterReservationDoesNotOpenAnyBrowser() throws Exception {
        var done=finished();
        assertTrue(jobs.startQueue(List.of(1L,2L),m -> {
            if (jobs.isRunning() && "running".equals(jobs.getQueue().state())) jobs.stopDelivery();
            if (!jobs.isRunning()) done.countDown();
        }));
        assertTrue(done.await(5,TimeUnit.SECONDS));
        verify(browsers,never()).ready(anyLong(),anyBoolean());
        assertEquals("stopped",jobs.getQueue().state());
        assertFalse(((BossProgressTracker.Snapshot)jobs.getStatus(1).get("progress")).isRunning());
    }
    @Test void duplicateAccountsAndInvalidConfigurationsNeverReserveOrLaunch() {
        assertThrows(IllegalArgumentException.class,() -> jobs.startQueue(List.of(1L,1L),ignored -> {}));
        when(configs.getBossConfig()).thenReturn(new BossConfig());
        assertThrows(IllegalArgumentException.class,() -> jobs.startQueue(List.of(1L,2L),ignored -> {}));
        assertFalse(locks.busy(1)); assertFalse(jobs.isRunning());
        verify(browsers,never()).ready(anyLong(),anyBoolean());
    }

    @Test void stopAtAccountBoundaryCannotStartTheNextBrowser() throws Exception {
        var done=finished();
        assertTrue(jobs.startQueue(List.of(1L,2L),message -> {
            var queue=jobs.getQueue();
            if (queue.isRunning() && Long.valueOf(2).equals(queue.activeAccountId())) jobs.stopDelivery();
            if (!jobs.isRunning()) done.countDown();
        }));
        assertTrue(done.await(5,TimeUnit.SECONDS));
        assertEquals(List.of(1L),execution);
        verify(browsers,never()).ready(2,false);
        assertEquals("stopped",jobs.getQueue().state());
    }

    @Test void dailyLimitEndsOnlyThatAccountAndKeepsActualCountsBeforeRunningNextAccount() throws Exception {
        when(provider.getObject()).thenAnswer(ignored -> {
            long id=BossAccountContext.id();
            var worker=mock(Boss.class);
            var progress=new AtomicReference<Boss.ProgressCallback>();
            doAnswer(c -> { progress.set(c.getArgument(0)); return null; }).when(worker).setProgressCallback(any());
            when(worker.execute()).thenAnswer(c -> {
                execution.add(BossAccountContext.id());
                if (id==1) {
                    progress.get().jobProcessed(true,1,20);
                    progress.get().jobProcessed(false,2,20);
                    throw new BossDailyLimitReachedException("您今天已与150位BOSS沟通，明天再来吧～");
                }
                return 2;
            });
            return worker;
        });
        var done=finished(); start(List.of(1L,2L),done); assertTrue(done.await(5,TimeUnit.SECONDS));
        assertEquals(List.of(1L,2L),execution);
        assertEquals("completed",jobs.getQueue().state());
        assertEquals(List.of("quota_reached","completed"),jobs.getQueue().items().stream().map(BossJobService.QueueItem::state).toList());
        assertEquals(List.of(1,2),jobs.getQueue().items().stream().map(BossJobService.QueueItem::deliveredCount).toList());
        var snapshot=(BossProgressTracker.Snapshot)jobs.getStatus(1).get("progress");
        assertEquals("quota_reached",snapshot.state()); assertFalse(snapshot.isRunning());
        assertEquals(2,snapshot.processedCount()); assertEquals(1,snapshot.deliveredCount());
        assertTrue(snapshot.message().contains("今日沟通已达上限"));
        assertFalse(locks.busy(1)); assertFalse(locks.busy(2));
    }

    @Test void allAccountsAtLimitFinishQueueAndManualStopStillTakesPriority() throws Exception {
        when(provider.getObject()).thenAnswer(ignored -> {
            var worker=mock(Boss.class);
            when(worker.execute()).thenAnswer(c -> {
                execution.add(BossAccountContext.id());
                throw new BossDailyLimitReachedException("您已达到沟通上限");
            });
            return worker;
        });
        var completed=finished(); start(List.of(1L,2L),completed); assertTrue(completed.await(5,TimeUnit.SECONDS));
        assertEquals(List.of(1L,2L),execution);
        assertEquals("completed",jobs.getQueue().state()); assertTrue(jobs.getQueue().message().contains("2 个账号"));
        execution.clear();
        when(provider.getObject()).thenAnswer(ignored -> {
            var worker=mock(Boss.class);
            when(worker.execute()).thenAnswer(c -> {
                execution.add(BossAccountContext.id()); jobs.stopDelivery();
                throw new BossDailyLimitReachedException("您已达到沟通上限");
            });
            return worker;
        });
        var stopped=finished(); start(List.of(1L,2L),stopped); assertTrue(stopped.await(5,TimeUnit.SECONDS));
        assertEquals(List.of(1L),execution);
        assertEquals("stopped",jobs.getQueue().state());
        assertEquals("stopped",jobs.getQueue().items().getFirst().state());
        assertEquals("cancelled",jobs.getQueue().items().get(1).state());
    }

    @Test void reviewBudgetEndsQueueAndKeepsActualCountsWithoutStartingTheNextAccount() throws Exception {
        when(provider.getObject()).thenAnswer(ignored -> {
            var worker=mock(Boss.class);var progress=new AtomicReference<Boss.ProgressCallback>();
            doAnswer(c -> {progress.set(c.getArgument(0));return null;}).when(worker).setProgressCallback(any());
            when(worker.execute()).thenAnswer(c -> {
                execution.add(BossAccountContext.id());progress.get().jobProcessed(true,1,20);
                throw new com.getjobs.worker.boss.review.BossReviewBudgetReachedException("本次岗位审核已达到 token 预算（1100 / 1000）");
            });return worker;
        });
        var done=finished();start(List.of(1L,2L),done);assertTrue(done.await(5,TimeUnit.SECONDS));
        assertEquals(List.of(1L),execution);assertEquals("budget_reached",jobs.getQueue().state());
        assertEquals("budget_reached",jobs.getQueue().items().getFirst().state());
        assertEquals(1,jobs.getQueue().items().getFirst().deliveredCount());assertEquals("cancelled",jobs.getQueue().items().get(1).state());
        verify(browsers,never()).ready(2,false);assertFalse(locks.busy(2));
    }
}
