package com.getjobs.application.controller;

import com.getjobs.application.service.BossAccountService;
import com.getjobs.worker.manager.BossBrowserSessions;
import com.getjobs.worker.service.BossJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/boss/accounts")
@CrossOrigin(origins="*")
@RequiredArgsConstructor
public class BossAccountController {
    private final BossAccountService accounts;
    private final BossBrowserSessions browsers;
    private final BossJobService jobs;
    public record CreateAccount(String name, Long copyFromAccountId) {}
    public record RenameAccount(String name) {}
    @GetMapping public List<Map<String,Object>> list() {
        return accounts.list().stream().map(a -> Map.<String,Object>of("id",a.id(),"name",a.name(),
                "defaultAccount",a.defaultAccount(),"isLoggedIn",browsers.loggedIn(a.id()))).toList();
    }
    @PostMapping public BossAccountService.Account create(@RequestBody CreateAccount request) {
        return accounts.create(request.name(),request.copyFromAccountId());
    }
    @PutMapping("/{id}") public Map<String,Object> rename(@PathVariable long id,@RequestBody RenameAccount request) {
        accounts.rename(id,request.name()); return Map.of("success",true);
    }
    @GetMapping("/{id}/profile") public BossAccountService.Profile profile(@PathVariable long id) { return accounts.profile(id); }
    @PutMapping("/{id}/profile") public Map<String,Object> saveProfile(@PathVariable long id,@RequestBody BossAccountService.Profile request) {
        accounts.saveProfile(id,request.introduce(),request.prompt()); return Map.of("success",true);
    }
    @PostMapping("/{id}/login") public Map<String,Object> login(@PathVariable long id) {
        // 浏览器线程执行整段任务时不能插入登录导航；避免请求排队后误打开其他账号。
        if (jobs.isRunning()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"请先停止 Boss 队列，再打开登录浏览器");
        browsers.ready(id,true);
        return Map.of("success",true,"isLoggedIn",browsers.loggedIn(id),"message","已打开该账号浏览器，请确认登录身份后扫码登录");
    }
    @PostMapping("/{id}/save-login") public Map<String,Object> saveLogin(@PathVariable long id) {
        if (jobs.isRunning()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"请先停止 Boss 任务或队列，再确认登录");
        browsers.ready(id,false);
        browsers.save(id);
        boolean loggedIn=browsers.loggedIn(id);
        return Map.of("success",true,"isLoggedIn",loggedIn,"message",loggedIn?"该账号登录已确认并保存":"尚未检测到登录，请打开该账号浏览器扫码后再确认");
    }
}
