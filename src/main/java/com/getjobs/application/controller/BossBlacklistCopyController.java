package com.getjobs.application.controller;

import com.getjobs.application.service.BossBlacklistCopyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestController
@CrossOrigin(origins="*")
@RequestMapping("/api/boss/config/blacklist")
@RequiredArgsConstructor
public class BossBlacklistCopyController {
    private final BossBlacklistCopyService copies;
    public record Request(Long sourceAccountId,List<String> types) {}
    @PostMapping("/copy") public BossBlacklistCopyService.Result copy(@RequestBody Request request) {
        if(request.sourceAccountId()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择来源账号");
        return copies.copy(request.sourceAccountId(),request.types());
    }
}
