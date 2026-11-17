package com.getjobs.worker.service;

import com.getjobs.worker.dto.JobProgressMessage;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/** 保存当前/最近一次任务的快照，页面刷新和 SSE 重连不影响任务。 */
public final class BossProgressTracker {
    private static final int LOG_LIMIT = 200;
    private final String instanceId = UUID.randomUUID().toString();
    private final ArrayDeque<LogEntry> logs = new ArrayDeque<>();
    private long revision;
    private String taskId;
    private String state = "idle";
    private String message = "尚未开始投递";
    private Long startedAt;
    private Long finishedAt;
    private long updatedAt = System.currentTimeMillis();
    private String city;
    private String keyword;
    private String jobName;
    private String companyName;
    private int current;
    private int total;
    private int processedCount;
    private int deliveredCount;
    private com.getjobs.worker.boss.BossCityProgress cityProgress;

    public record LogEntry(long id, String type, String message, long timestamp) {}
    public record Snapshot(String instanceId, long revision, String taskId, String state,
                           boolean isRunning, Long startedAt, Long finishedAt, long updatedAt,
                           long elapsedMillis, String message, String city, String keyword,
                           String jobName, String companyName, int current, int total,
                           int processedCount, int deliveredCount, int skippedCount,
                           List<LogEntry> logs, com.getjobs.worker.boss.BossCityProgress cityProgress) {}

    public synchronized String begin() {
        if (isRunning()) return null;
        taskId = UUID.randomUUID().toString();
        state = "starting";
        startedAt = System.currentTimeMillis();
        finishedAt = null;
        city = keyword = jobName = companyName = null;
        current = total = processedCount = deliveredCount = 0;
        cityProgress=null;
        logs.clear();
        record(JobProgressMessage.info("boss", "正在准备投递任务"));
        return taskId;
    }

    public synchronized boolean isRunning() {
        return "starting".equals(state) || "running".equals(state) || "stopping".equals(state);
    }

    public synchronized boolean shouldStop() { return "stopping".equals(state); }
    public synchronized boolean owns(String id) { return id != null && id.equals(taskId); }

    public synchronized void running(String id) {
        if (owns(id) && "starting".equals(state)) {
            state = "running";
            changed();
        }
    }

    public synchronized boolean requestStop() {
        if (!isRunning() || shouldStop()) return false;
        state = "stopping";
        changed();
        return true;
    }

    public synchronized void search(String id, String city, String keyword) {
        if (!owns(id)) return;
        this.city = city;
        this.keyword = keyword;
        jobName = companyName = null;
        current = total = 0;
        changed();
    }

    public synchronized void job(String id, String name, String company, int total) {
        if (!owns(id)) return;
        jobName = name;
        companyName = company;
        this.total = Math.max(0, total);
        changed();
    }

    public synchronized void processed(String id, boolean delivered, int current, int total) {
        if (!owns(id)) return;
        processedCount++;
        if (delivered) deliveredCount++;
        this.current = Math.max(0, current);
        this.total = Math.max(0, total);
        changed();
    }
    public synchronized void cities(String id,com.getjobs.worker.boss.BossCityProgress progress){if(owns(id)){cityProgress=progress;changed();}}

    public synchronized void record(JobProgressMessage event) {
        changed();
        message = event.getMessage();
        if (event.getTotal() != null && event.getTotal() > 0) total = event.getTotal();
        logs.addLast(new LogEntry(revision, event.getType(), message, updatedAt));
        while (logs.size() > LOG_LIMIT) logs.removeFirst();
    }

    public synchronized void finish(String id, String terminalState, int delivered) {
        if (!owns(id)) return;
        state = terminalState;
        deliveredCount = delivered;
        finishedAt = System.currentTimeMillis();
        changed();
    }

    private void changed() {
        revision++;
        updatedAt = System.currentTimeMillis();
    }

    public synchronized Snapshot snapshot() {
        long elapsed = startedAt == null ? 0 : Math.max(0,
                (finishedAt == null ? System.currentTimeMillis() : finishedAt) - startedAt);
        return new Snapshot(instanceId, revision, taskId, state, isRunning(), startedAt, finishedAt,
                updatedAt, elapsed, message, city, keyword, jobName, companyName, current, total,
                processedCount, deliveredCount, Math.max(0, processedCount - deliveredCount), List.copyOf(logs),cityProgress);
    }
}
