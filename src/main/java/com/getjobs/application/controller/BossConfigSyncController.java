package com.getjobs.application.controller;

import com.getjobs.application.service.BossConfigSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/boss/accounts/{accountId}/sync-config")
@RequiredArgsConstructor
public class BossConfigSyncController {
    private final BossConfigSyncService syncService;

    public record Request(Long sourceAccountId) {}

    @PostMapping
    public BossConfigSyncService.Result sync(@PathVariable long accountId, @RequestBody Request request) {
        if (request.sourceAccountId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择来源账号");
        }
        return syncService.sync(request.sourceAccountId(), accountId);
    }
}
