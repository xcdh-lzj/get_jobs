package com.getjobs.application.controller;

import com.getjobs.application.service.BossAccountContext;
import com.getjobs.application.service.BossAccountLocks;
import com.getjobs.application.service.BossAccountService;
import com.getjobs.application.service.BossFollowUpStore;
import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.boss.BossFollowUpWorker;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.BossBrowserSessions;
import com.getjobs.worker.manager.BossInboxService;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossFollowUpService;
import com.getjobs.worker.service.BossJobService;
import com.getjobs.worker.service.BossProgressTracker;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BossProgressControllerTest {
    @TempDir Path directory;

    @Test void streamIncludesSnapshotAndSubsequentTaskEvents() throws Exception {
        var jobs = mock(BossJobService.class);
        var manager = mock(BossBrowserSessions.class);
        var tracker = new BossProgressTracker();
        String id = tracker.begin();
        tracker.record(JobProgressMessage.info("boss", "已加载岗位"));
        when(jobs.getStatus(1L)).thenAnswer(ignored -> Map.of("platform", "boss", "isRunning", tracker.isRunning(),
                "isLoggedIn", true, "progress", tracker.snapshot()));
        when(manager.loggedIn(1L)).thenReturn(true);
        var callback = new AtomicReference<Consumer<JobProgressMessage>>();
        when(jobs.startDelivery(eq(1L),any())).thenAnswer(call -> { callback.set(call.getArgument(1)); return true; });
        var mvc = MockMvcBuilders.standaloneSetup(new BossController(jobs, manager, mock(BossFollowUpService.class))).build();
        var stream = mvc.perform(get("/api/boss/stream")).andExpect(request().asyncStarted()).andReturn();
        try {
            String initial = stream.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(initial.contains("event:connected"));
            assertTrue(initial.contains("已加载岗位"));
            assertTrue(initial.contains(id));
            mvc.perform(post("/api/boss/start")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
            tracker.finish(id, "completed", 3);
            var done = JobProgressMessage.success("boss", "任务完成");
            tracker.record(done);
            callback.get().accept(done);
            String body = stream.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.contains("event:progress"));
            assertTrue(body.contains("\"state\":\"completed\""));
            assertTrue(body.contains("\"deliveredCount\":3"));
            mvc.perform(get("/api/boss/status")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.progress.state").value("completed"))
                    .andExpect(jsonPath("$.progress.logs").isArray());
        } finally { stream.getRequest().getAsyncContext().complete(); }
    }

    @Test void canReadAndStopFollowUpWhileTheDatabaseFinishTimeIsNull() throws Exception {
        var jobs = mock(BossJobService.class);
        var manager = mock(PlaywrightManager.class);
        var configs = mock(ConfigService.class);
        var browsers = mock(BossBrowserSessions.class);
        var worker = mock(BossFollowUpWorker.class);
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("follow-up.db"));
        var store = spy(new BossFollowUpStore(new JdbcTemplate(source)));
        store.migrate();
        var followUp = new BossFollowUpService(manager, configs, mock(BossAccountService.class), browsers,
                new BossAccountLocks(), store, worker, mock(BossInboxService.class));
        when(configs.getBossConfig()).thenReturn(new BossConfig());
        when(browsers.loggedIn(2L)).thenReturn(true);
        when(browsers.ready(2L, false)).thenReturn(mock(Page.class));
        when(manager.callOnPlaywright(any())).thenAnswer(call -> call.<Callable<?>>getArgument(0).call());
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        when(worker.execute(any(), any(), any(), any(), any())).thenAnswer(call -> {
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new BossFollowUpWorker.Result("stopped", 0, 0, 0, 0, "跟进已停止");
        });
        doAnswer(call -> { call.callRealMethod(); finished.countDown(); return null; })
                .when(store).finish(anyLong(), eq("stopped"), anyInt(), anyInt(), anyInt(), anyInt(), anyString());
        var mvc = MockMvcBuilders.standaloneSetup(new BossController(jobs, browsers, followUp)).build();
        try (var scope = BossAccountContext.use(2L)) {
            mvc.perform(post("/api/boss/follow-up/start")).andExpect(status().isOk());
            mvc.perform(get("/api/boss/follow-up")).andExpect(status().isOk()).andExpect(jsonPath("$.activeAccountId").value(2))
                    .andExpect(jsonPath("$.lastRun.finishedAt").value(nullValue()));
            mvc.perform(post("/api/boss/follow-up/stop")).andExpect(status().isOk());
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            mvc.perform(get("/api/boss/follow-up")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.activeAccountId").value(nullValue())).andExpect(jsonPath("$.lastRun.state").value("stopped"));
            long runId = store.history(2L, null).getFirst().id();
            mvc.perform(get("/api/boss/follow-up/runs/" + runId + "/logs")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[1].message").value("跟进已停止"));
        } finally {
            release.countDown();
        }
        verifyNoInteractions(jobs);
    }
}
