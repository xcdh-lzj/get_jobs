package com.getjobs.application.controller;

import com.getjobs.application.service.BossAccountContext;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.BossBrowserSessions;
import com.getjobs.worker.service.BossFollowUpService;
import com.getjobs.worker.service.BossJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/boss")
@CrossOrigin(origins="*")
@RequiredArgsConstructor
public class BossController {
    private final BossJobService jobs;
    private final BossBrowserSessions browsers;
    private final BossFollowUpService followUps;
    private final Map<SseEmitter,Long> emitters=new ConcurrentHashMap<>();

    @GetMapping(value="/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBossProgress() {
        long accountId=BossAccountContext.id();
        SseEmitter emitter=new SseEmitter(0L);
        emitters.put(emitter,accountId);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter,"connected",Map.of("message","已连接到 Boss 账号进度","status",jobs.getStatus(accountId)));
        return emitter;
    }
    @PostMapping({"/start","/execute"})
    public ResponseEntity<Map<String,Object>> startBoss() {
        long accountId=BossAccountContext.id();
        if (!browsers.loggedIn(accountId)) return error(HttpStatus.BAD_REQUEST,"请先打开该账号浏览器并登录");
        try {
            if (!jobs.startDelivery(accountId,this::sendProgress)) return error(HttpStatus.CONFLICT,"Boss 任务或队列已在运行中");
            return ResponseEntity.ok(Map.of("success",true,"status","started","message","账号投递任务已启动"));
        } catch (IllegalArgumentException e) { return error(HttpStatus.BAD_REQUEST,e.getMessage()); }
    }
    @PostMapping("/stop")
    public ResponseEntity<Map<String,Object>> stopBoss() {
        var queue=jobs.getQueue();
        if (!queue.isRunning()) return error(HttpStatus.BAD_REQUEST,"没有正在运行的 Boss 任务");
        if (!Objects.equals(queue.activeAccountId(),BossAccountContext.id())) return error(HttpStatus.CONFLICT,"该账号没有执行任务，请通过队列面板停止当前队列");
        jobs.stopDelivery();
        return ResponseEntity.ok(Map.of("success",true,"message","已请求停止，后续账号不会启动"));
    }
    @PostMapping("/review/scan")
    public ResponseEntity<Map<String,Object>> scanOnly() {
        long accountId=BossAccountContext.id();
        if (!browsers.loggedIn(accountId)) return error(HttpStatus.BAD_REQUEST,"请先确认该账号登录");
        try {
            if (!jobs.startReviewOnly(accountId,this::sendProgress)) return error(HttpStatus.CONFLICT,"Boss 任务或队列正在执行");
            return ResponseEntity.ok(Map.of("success",true,"message","已启动列表采集与岗位审核，不会发起沟通或投递"));
        } catch(IllegalArgumentException invalid) { return error(HttpStatus.BAD_REQUEST,invalid.getMessage()); }
    }
    @PostMapping("/logout")
    public Map<String,Object> logoutBoss() {
        if (jobs.isRunning() || followUps.isRunning()) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,"请先停止 Boss 投递或跟进任务，再退出登录");
        browsers.logout(BossAccountContext.id());
        return Map.of("success",true,"message","该账号已退出登录");
    }
    @GetMapping("/status") public Map<String,Object> getBossStatus() { return jobs.getStatus(BossAccountContext.id()); }
    @GetMapping("/follow-up") public BossFollowUpService.Status followUp() {
        return followUps.status(BossAccountContext.id());
    }
    @PostMapping("/follow-up/start")
    public ResponseEntity<Map<String,Object>> startFollowUp() {
        try {
            followUps.start(BossAccountContext.id());
            return ResponseEntity.ok(Map.of("success",true,"message","跟进任务已启动"));
        } catch (IllegalArgumentException e) { return error(HttpStatus.BAD_REQUEST,e.getMessage()); }
    }
    @PostMapping("/follow-up/stop")
    public ResponseEntity<Map<String,Object>> stopFollowUp() {
        if (!followUps.stop(BossAccountContext.id())) return error(HttpStatus.BAD_REQUEST,"当前账号没有正在执行的跟进任务");
        return ResponseEntity.ok(Map.of("success",true,"message","已请求停止跟进"));
    }
    public record QueueRequest(List<Long> accountIds) {}
    @GetMapping("/queue") public BossJobService.QueueSnapshot queue() { return jobs.getQueue(); }
    @PostMapping("/queue/start")
    public ResponseEntity<Map<String,Object>> startQueue(@RequestBody QueueRequest request) {
        try {
            // 未打开的账号可以直接复用磁盘登录态；轮到该账号时再实际检查，失效则停止队列。
            if (!jobs.startQueue(request.accountIds(),this::sendProgress)) return error(HttpStatus.CONFLICT,"Boss 任务或队列已在运行中");
            return ResponseEntity.ok(Map.of("success",true,"message","队列已启动，将按列表顺序执行"));
        } catch (IllegalArgumentException e) { return error(HttpStatus.BAD_REQUEST,e.getMessage()); }
    }
    @PostMapping("/queue/stop") public Map<String,Object> stopQueue() {
        jobs.stopDelivery(); return Map.of("success",true,"message","停止请求已发送，后续账号不会启动");
    }
    private ResponseEntity<Map<String,Object>> error(HttpStatus status,String message) {
        return ResponseEntity.status(status).body(Map.of("success",false,"message",message));
    }
    private void sendProgress(JobProgressMessage message) {
        emitters.forEach((emitter,id) -> send(emitter,"progress",Map.of("status",jobs.getStatus(id))));
    }
    private void send(SseEmitter emitter,String event,Object payload) {
        try { emitter.send(SseEmitter.event().name(event).data(payload)); }
        catch (Exception e) {
            emitters.remove(emitter);
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }
    @Scheduled(fixedRate=3000) public void heartbeatBossProgress() {
        emitters.forEach((emitter,id) -> send(emitter,"status",Map.of("status",jobs.getStatus(id))));
    }
}
