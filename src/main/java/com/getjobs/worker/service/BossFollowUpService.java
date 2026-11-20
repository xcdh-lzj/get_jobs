package com.getjobs.worker.service;

import com.getjobs.application.service.BossAccountContext;
import com.getjobs.application.service.BossAccountLocks;
import com.getjobs.application.service.BossAccountService;
import com.getjobs.application.service.BossFollowUpStore;
import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.boss.BossFollowUpWorker;
import com.getjobs.worker.manager.BossBrowserSessions;
import com.getjobs.worker.manager.PlaywrightManager;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 手动启动和定期触发共用跟进任务，独立于投递队列。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BossFollowUpService {
    private final PlaywrightManager manager;
    private final ConfigService configs;
    private final BossAccountService accounts;
    private final BossBrowserSessions browsers;
    private final BossAccountLocks locks;
    private final BossFollowUpStore store;
    private final BossFollowUpWorker worker;
    private volatile Task active;

    public record Progress(int checked, int total, int confirmed, String message, String jobName) {}
    public record Status(Long activeAccountId, boolean stopping, Progress progress, BossFollowUpStore.Run lastRun) {}

    private static final class Task implements Boss.ProgressCallback {
        final long accountId;
        volatile boolean stopping;
        volatile Progress progress = new Progress(0, 0, 0, "正在准备跟进任务", null);

        Task(long accountId) { this.accountId = accountId; }

        @Override public void accept(String message, Integer current, Integer total) {
            var previous = progress;
            progress = new Progress(previous.checked(), total == null ? previous.total() : total,
                    previous.confirmed(), message, previous.jobName());
        }

        @Override public void jobStarted(String name, String company, int total) {
            var previous = progress;
            progress = new Progress(previous.checked(), total, previous.confirmed(), "正在核对会话", name);
        }

        @Override public void jobProcessed(boolean sent, int current, int total) {
            var previous = progress;
            progress = new Progress(current, total, previous.confirmed() + (sent ? 1 : 0), previous.message(), previous.jobName());
        }
    }

    public void start(long id) {
        accounts.require(id);
        start(id, false);
    }

    private boolean start(long id, boolean scheduled) {
        if (!browsers.loggedIn(id)) {
            if (scheduled) return false;
            throw new IllegalArgumentException("请先登录当前 Boss 账号");
        }
        locks.reserve(List.of(id));
        boolean launched = false;
        try (var scope = BossAccountContext.use(id)) {
            BossConfig config = configs.getBossConfig();
            String unavailable = unavailableReason(config);
            if (scheduled && (unavailable != null || !config.getFollowUpPolicy().enabled()
                    || !store.due(id, config.getFollowUpPolicy(), System.currentTimeMillis()))) return false;
            if (unavailable != null) throw new IllegalArgumentException(unavailable);
            config.getFollowUpPolicy().validated();
            synchronized (this) {
                store.start(id);
                var task = new Task(id);
                active = task;
                try {
                    CompletableFuture.runAsync(() -> execute(task, config));
                    launched = true;
                } catch (RuntimeException error) {
                    active = null;
                    store.finish(id, "failed", 0, 0, 0, "无法启动跟进任务");
                    throw error;
                }
            }
        } finally {
            if (!launched) locks.release();
        }
        return true;
    }

    private static String unavailableReason(BossConfig config) {
        if (config == null) return "请先保存当前账号的跟进配置";
        if (Boolean.TRUE.equals(config.getDebugger())) return "当前账号启用了调试模式，请关闭并保存后启动跟进";
        if (config.getRepeatChatMessages() == null || config.getRepeatChatMessages().isBlank()) {
            return "请至少填写一条跟进消息并保存";
        }
        return null;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void checkSchedule() {
        if (!manager.isInitialized() || locks.isReserved()) return;
        for (var account : accounts.list()) {
            long id = account.id();
            if (!browsers.loggedIn(id)) continue;
            try (var scope = BossAccountContext.use(id)) {
                var config = configs.getBossConfig();
                if (unavailableReason(config) == null && config.getFollowUpPolicy().enabled()
                        && store.due(id, config.getFollowUpPolicy(), System.currentTimeMillis()) && start(id, true)) return;
            } catch (ResponseStatusException conflict) {
                if (conflict.getStatusCode() == HttpStatus.CONFLICT) return;
                log.warn("无法启动 Boss 账号 {} 的定期跟进", id, conflict);
            } catch (RuntimeException error) {
                log.warn("无法启动 Boss 账号 {} 的定期跟进", id, error);
            }
        }
    }

    private void execute(Task task, BossConfig config) {
        long id = task.accountId;
        BossFollowUpWorker.Result result = new BossFollowUpWorker.Result("failed", 0, 0, 0, "跟进任务异常结束");
        boolean monitoringPaused = false;
        try (var scope = BossAccountContext.use(id)) {
            if (task.stopping) {
                result = stopped();
                return;
            }
            var page = browsers.ready(id, false);
            if (!browsers.loggedIn(id)) throw new IllegalStateException("账号登录已失效");
            if (id == 1) { manager.pauseBossMonitoring(); monitoringPaused = true; }
            result = manager.callOnPlaywright(() -> {
                try (var workerScope = BossAccountContext.use(id)) {
                    return task.stopping ? stopped() : worker.execute(page, config, () -> task.stopping, task);
                }
            });
        } catch (Exception error) {
            log.warn("Boss 账号 {} 跟进任务中止", id, error);
            var progress = task.progress;
            result = new BossFollowUpWorker.Result(task.stopping ? "stopped" : "failed", progress.checked(), progress.confirmed(), 0,
                    task.stopping ? "跟进任务已停止" : "跟进任务中止，请检查账号登录和浏览器状态");
        } finally {
            try {
                if (monitoringPaused) manager.resumeBossMonitoring();
            } finally {
                finish(task, result);
            }
        }
    }

    private static BossFollowUpWorker.Result stopped() {
        return new BossFollowUpWorker.Result("stopped", 0, 0, 0, "跟进任务已停止");
    }

    private synchronized void finish(Task task, BossFollowUpWorker.Result result) {
        try {
            if (task.stopping && "completed".equals(result.state())) {
                result = new BossFollowUpWorker.Result("stopped", result.checked(), result.confirmed(), result.unconfirmed(),
                        "跟进任务已停止。" + result.message());
            }
            store.finish(task.accountId, result.state(), result.checked(), result.confirmed(), result.unconfirmed(), result.message());
        } catch (RuntimeException error) {
            log.error("无法保存 Boss 账号 {} 的跟进结果", task.accountId, error);
        } finally {
            active = null;
            locks.release();
        }
    }

    public boolean isRunning() { return active != null; }

    public synchronized Status status(long id) {
        Task task = active;
        return new Status(task == null ? null : task.accountId, task != null && task.stopping,
                task != null && task.accountId == id ? task.progress : null, store.lastRun(id));
    }

    public synchronized boolean stop(long id) {
        if (active == null || active.accountId != id) return false;
        active.stopping = true;
        return true;
    }

    @PreDestroy public synchronized void shutdown() {
        if (active != null) active.stopping = true;
    }
}
