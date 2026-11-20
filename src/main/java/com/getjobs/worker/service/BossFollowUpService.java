package com.getjobs.worker.service;

import com.getjobs.application.service.BossAccountContext;
import com.getjobs.application.service.BossAccountLocks;
import com.getjobs.application.service.BossAccountService;
import com.getjobs.application.service.BossFollowUpStore;
import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.boss.BossFollowUpPolicy;
import com.getjobs.worker.boss.BossFollowUpWorker;
import com.getjobs.worker.manager.BossBrowserSessions;
import com.getjobs.worker.manager.BossInboxService;
import com.getjobs.worker.manager.PlaywrightManager;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** 固定时刻产生待执行记录，浏览器空闲后按顺序核对各轮的全部候选会话。 */
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
    private final BossInboxService inbox;
    private volatile Task active;
    private long scannedMinute = Long.MIN_VALUE;

    public record Status(Long activeAccountId, Long activeRunId, boolean stopping, BossFollowUpWorker.Progress progress,
                         BossFollowUpStore.Run lastRun, List<BossFollowUpStore.Run> recentRuns,
                         Long nextScheduledAt, String timeZone) {}

    private static final class Task {
        final long accountId;
        final long runId;
        final BossFollowUpPolicy.Window window;
        volatile boolean stopping;
        volatile BossFollowUpWorker.Progress progress = new BossFollowUpWorker.Progress(
                0, 0, 0, 0, 0, "正在准备跟进任务", null, null);

        Task(long accountId, long runId, BossFollowUpPolicy.Window window) {
            this.accountId = accountId;
            this.runId = runId;
            this.window = window;
        }
    }

    public void start(long id) {
        accounts.require(id);
        if (!browsers.loggedIn(id)) throw new IllegalArgumentException("请先登录当前 Boss 账号");
        locks.reserve(List.of(id));
        boolean launched = false;
        long runId = 0;
        try (var scope = BossAccountContext.use(id)) {
            BossConfig config = configs.getBossConfig();
            String unavailable = unavailableReason(config);
            if (unavailable != null) throw new IllegalArgumentException(unavailable);
            long now = System.currentTimeMillis();
            var window = config.getFollowUpPolicy().validated().window(now);
            runId = store.startManual(id, window.dateLabels(), now);
            launch(new Task(id, runId, window), config);
            launched = true;
        } catch (RuntimeException error) {
            if (runId != 0) reject(runId, "failed", "无法启动手动跟进任务");
            throw error;
        } finally {
            if (!launched) locks.release();
        }
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 15_000)
    public void checkSchedule() {
        checkScheduleAt(System.currentTimeMillis());
    }

    void checkScheduleAt(long now) {
        long minute = Math.floorDiv(now, 60_000);
        if (minute != scannedMinute) {
            enqueueCurrentMinute(now);
            scannedMinute = minute;
        }
        if (locks.isReserved()) return;
        for (var plan : store.pending()) {
            try {
                if (startScheduled(plan)) return;
            } catch (ResponseStatusException busy) {
                if (busy.getStatusCode() != HttpStatus.CONFLICT) throw busy;
                return;
            }
        }
    }

    private void enqueueCurrentMinute(long now) {
        for (var account : accounts.list()) {
            long id = account.id();
            try (var scope = BossAccountContext.use(id)) {
                var config = configs.getBossConfig();
                if (config == null) continue;
                var policy = config.getFollowUpPolicy();
                Long slot = policy.slotAt(now);
                if (slot != null) store.enqueue(id, slot, policy.window(now, slot).dateLabels(), now);
            } catch (RuntimeException error) {
                log.warn("读取 Boss 账号 {} 的跟进计划失败", id, error);
            }
        }
    }

    private boolean startScheduled(BossFollowUpStore.Run plan) {
        long id = plan.accountId();
        locks.reserve(List.of(id));
        boolean launched = false;
        try (var scope = BossAccountContext.use(id)) {
            var config = configs.getBossConfig();
            long now = System.currentTimeMillis();
            var policy = config == null ? BossFollowUpPolicy.defaults() : config.getFollowUpPolicy();
            long slot = plan.scheduledAt();
            var window = policy.window(now, slot);
            if (!Objects.equals(policy.slotAt(slot), slot) || !window.dateLabels().equals(plan.dates())) {
                reject(plan.id(), "skipped", "跟进计划已关闭或调整，本次排队任务已取消");
                return false;
            }
            String unavailable = unavailableReason(config);
            if (unavailable != null) {
                reject(plan.id(), "failed", unavailable);
                return false;
            }
            if (!browsers.loggedIn(id)) {
                reject(plan.id(), "failed", "账号未登录或浏览器未打开，请登录 Boss 后再跟进");
                return false;
            }
            if (!store.startScheduled(plan.id(), now)) return false;
            launch(new Task(id, plan.id(), window), config);
            launched = true;
            return true;
        } catch (RuntimeException error) {
            log.warn("无法启动 Boss 账号 {} 的定时跟进", id, error);
            reject(plan.id(), "failed", "无法启动定时跟进，请检查账号配置和服务日志");
            return false;
        } finally {
            if (!launched) locks.release();
        }
    }

    private void reject(long runId, String state, String message) {
        store.finish(runId, state, 0, 0, 0, 0, message);
    }

    private synchronized void launch(Task task, BossConfig config) {
        active = task;
        try {
            CompletableFuture.runAsync(() -> execute(task, config));
        } catch (RuntimeException error) {
            active = null;
            throw error;
        }
    }

    private static String unavailableReason(BossConfig config) {
        if (config == null) return "请先保存当前账号的跟进配置";
        if (Boolean.TRUE.equals(config.getDebugger())) return "当前账号已开启调试模式，请关闭并保存后再跟进";
        if (config.getRepeatChatMessages() == null || config.getRepeatChatMessages().isBlank())
            return "请至少填写一条跟进消息并保存";
        return null;
    }

    private void record(Task task, BossFollowUpWorker.Progress update) {
        task.progress = update;
        if (update.level() != null) {
            String message = update.jobName() == null || update.jobName().isBlank()
                    ? update.message() : update.jobName() + " · " + update.message();
            store.progress(task.runId, update.total(), update.checked(), update.confirmed(),
                    update.unconfirmed(), update.failed(), update.level(), message);
        }
    }

    private void execute(Task task, BossConfig config) {
        long id = task.accountId;
        var result = new BossFollowUpWorker.Result("failed", 0, 0, 0, 0, "跟进任务异常结束");
        boolean monitoringPaused = false;
        try (var scope = BossAccountContext.use(id)) {
            if (task.stopping) {
                result = stopped();
                return;
            }
            var page = browsers.ready(id, false);
            if (!browsers.loggedIn(id)) {
                result = new BossFollowUpWorker.Result("failed", 0, 0, 0, 0, "账号登录已失效，请重新登录 Boss");
                return;
            }
            if (id == 1) { manager.pauseBossMonitoring(); monitoringPaused = true; }
            result = manager.callOnPlaywright(() -> {
                try (var workerScope = BossAccountContext.use(id)) {
                    return task.stopping ? stopped() : worker.execute(page, config, task.window, () -> {
                        inbox.checkpoint();
                        return task.stopping;
                    }, update -> record(task, update));
                }
            });
        } catch (Exception error) {
            log.warn("Boss 账号 {} 跟进任务中止", id, error);
            var progress = task.progress;
            result = new BossFollowUpWorker.Result(task.stopping ? "stopped" : "failed", progress.checked(),
                    progress.confirmed(), progress.unconfirmed(), progress.failed(),
                    task.stopping ? "跟进任务已停止" : "跟进任务中止，请检查账号登录、浏览器和服务日志");
        } finally {
            try {
                if (monitoringPaused) manager.resumeBossMonitoring();
            } finally {
                finish(task, result);
            }
        }
    }

    private static BossFollowUpWorker.Result stopped() {
        return new BossFollowUpWorker.Result("stopped", 0, 0, 0, 0, "跟进任务已停止");
    }

    private synchronized void finish(Task task, BossFollowUpWorker.Result result) {
        try {
            boolean requestedStop = task.stopping && List.of("completed", "partial").contains(result.state());
            store.finish(task.runId, requestedStop ? "stopped" : result.state(), result.checked(), result.confirmed(),
                    result.unconfirmed(), result.failed(), requestedStop ? "跟进任务已停止。" + result.message() : result.message());
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
        var runs = history(id, null);
        Long next = null;
        try (var scope = BossAccountContext.use(id)) {
            var config = configs.getBossConfig();
            if (config != null) next = config.getFollowUpPolicy().nextAfter(System.currentTimeMillis());
        } catch (RuntimeException error) {
            log.debug("无法读取 Boss 账号 {} 的下次跟进时间", id);
        }
        boolean current = task != null && task.accountId == id;
        return new Status(task == null ? null : task.accountId, current ? task.runId : null,
                task != null && task.stopping, current ? task.progress : null,
                runs.isEmpty() ? null : runs.getFirst(), runs, next, ZoneId.systemDefault().getId());
    }

    public List<BossFollowUpStore.Run> history(long id, Long before) { return store.history(id, before); }
    public List<BossFollowUpStore.Log> logs(long id, long runId) { return store.logs(id, runId); }

    public synchronized boolean stop(long id) {
        if (active == null || active.accountId != id) return false;
        active.stopping = true;
        return true;
    }

    @PreDestroy public synchronized void shutdown() {
        if (active != null) active.stopping = true;
    }
}
