package com.getjobs.application.controller;

import com.getjobs.application.service.BossAccountContext;
import com.getjobs.worker.manager.BossInboxService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/boss/inbox")
@RequiredArgsConstructor
public class BossInboxController {
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private final BossInboxService inbox;
    private final Set<SseEmitter> streams = ConcurrentHashMap.newKeySet();
    public record StartRequest(List<Long> accountIds) {}

    @GetMapping
    public BossInboxService.Snapshot status(HttpServletRequest request) {
        requireWorkbench(request);
        return inbox.snapshot();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request) {
        var snapshot = status(request);
        SseEmitter stream = new SseEmitter(0L);
        stream.onCompletion(() -> streams.remove(stream));
        stream.onTimeout(() -> streams.remove(stream));
        stream.onError(ignored -> streams.remove(stream));
        send(stream, snapshot);
        streams.add(stream);
        return stream;
    }

    @PostMapping("/start")
    public BossInboxService.Snapshot start(HttpServletRequest request, @RequestBody(required = false) StartRequest selection) {
        requireWorkbench(request);
        inbox.start(selection == null ? null : selection.accountIds());
        return inbox.snapshot();
    }

    @PostMapping("/stop")
    public BossInboxService.Snapshot stop(HttpServletRequest request) {
        requireWorkbench(request);
        inbox.stop();
        return inbox.snapshot();
    }

    @PostMapping("/open")
    public CompletableFuture<BossInboxService.Reply> open(HttpServletRequest request) {
        requireWorkbench(request);
        return inbox.open(BossAccountContext.id(), false);
    }

    @PostMapping("/refresh-unread")
    public CompletableFuture<BossInboxService.Reply> refresh(HttpServletRequest request) {
        requireWorkbench(request);
        return inbox.open(BossAccountContext.id(), true);
    }

    @Scheduled(fixedRate = 2_000)
    public void broadcast() {
        if (streams.isEmpty()) return;
        var snapshot = inbox.snapshot();
        streams.forEach(stream -> send(stream, snapshot));
    }

    private void send(SseEmitter stream, BossInboxService.Snapshot snapshot) {
        try {
            stream.send(SseEmitter.event().name("inbox").data(snapshot));
        } catch (Exception closed) {
            streams.remove(stream);
            stream.complete();
        }
    }

    private static void requireWorkbench(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) return;
        try {
            URI source = URI.create(origin);
            String host = source.getHost();
            if (host != null && ("http".equals(source.getScheme()) || "https".equals(source.getScheme()))
                    && (LOOPBACK.contains(host) && LOOPBACK.contains(request.getServerName())
                    || host.equalsIgnoreCase(request.getServerName()) && request.getServerPort() == originPort(source))) return;
        } catch (IllegalArgumentException ignored) {}
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请在 Get Jobs 工作台操作消息监听");
    }

    private static int originPort(URI origin) {
        if (origin.getPort() >= 0) return origin.getPort();
        return "https".equals(origin.getScheme()) ? 443 : 80;
    }
}
