package com.getjobs.application.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice(assignableTypes={BossController.class,BossAccountController.class,BossConfigController.class,BossAnalyticsController.class,BossReviewController.class,BossBlacklistCopyController.class})
public class BossApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,Object>> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("success",false,"message",error.getReason()==null?"请求失败":error.getReason()));
    }
}
