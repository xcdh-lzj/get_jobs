package com.getjobs.application.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.BossBlacklistCopyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

@RestController
@CrossOrigin(origins="*")
@RequestMapping("/api/boss/config/blacklist")
@RequiredArgsConstructor
public class BossBlacklistCopyController {
    private final BossBlacklistCopyService copies;
    private final ObjectMapper json;

    public record Request(Long sourceAccountId,List<String> types) {}
    @PostMapping("/copy") public BossBlacklistCopyService.Result copy(@RequestBody Request request) {
        if(request.sourceAccountId()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择来源账号");
        return copies.copy(request.sourceAccountId(),request.types());
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportBlacklist() throws JsonProcessingException {
        byte[] data = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(copies.exportBlacklist());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("boss_blacklist.json").build().toString())
                .body(data);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BossBlacklistCopyService.ImportResult importBlacklist(@RequestParam("file") MultipartFile file) {
        JsonNode document;
        try (var input = file.getInputStream()) {
            document = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(input);
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取黑名单 JSON 文件，请检查文件格式");
        }
        return copies.importBlacklist(document);
    }
}
