package com.getjobs.application.controller;

import com.getjobs.application.service.CookieService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.BossBrowserSessions;
import com.getjobs.worker.service.BossJobService;
import com.getjobs.worker.service.BossProgressTracker;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BossProgressControllerTest {
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
        var mvc = MockMvcBuilders.standaloneSetup(new BossController(jobs, manager)).build();
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
}
