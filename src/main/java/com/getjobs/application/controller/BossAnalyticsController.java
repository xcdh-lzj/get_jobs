package com.getjobs.application.controller;

import com.getjobs.application.service.BossAccountContext;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.BossService.HistoryFilters;
import com.getjobs.application.service.BossService.HistorySort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/boss")
public class BossAnalyticsController {

    private final BossService bossService;

    public BossAnalyticsController(BossService bossService) {
        this.bossService = bossService;
    }

    /**
     * 投递分析统计与图表（支持与列表相同的筛选条件）
     */
    @GetMapping("/stats")
    public BossService.StatsResponse getStats(@ModelAttribute(binding = false) HistoryFilters filters) {
        validateDateRange(filters);
        return bossService.getBossStats(filters);
    }

    /**
     * 岗位列表（分页 + 筛选）
     */
    @GetMapping("/list")
    public BossService.PagedResult list(
            @ModelAttribute(binding = false) HistoryFilters filters,
            @RequestParam(value = "sort", defaultValue = "RECORDED_DESC") HistorySort sort,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        validateDateRange(filters);
        return bossService.listBossJobs(filters, sort, page, size);
    }

    @GetMapping(value = "/jobs.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(
            @ModelAttribute(binding = false) HistoryFilters filters,
            @RequestParam(value = "sort", defaultValue = "RECORDED_DESC") HistorySort sort
    ) {
        validateDateRange(filters);
        var jobs = bossService.exportBossJobs(filters, sort);
        String name = "boss_jobs_" + BossAccountContext.id() + "_" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(BossJobCsv.encode(jobs));
    }

    private static void validateDateRange(HistoryFilters filters) {
        var from = filters.deliveredFrom();
        var to = filters.deliveredTo();
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "投递起始日期须早于或等于结束日期");
        }
    }

    /**
     * 刷新 boss_data（列顺序检查 + VACUUM）
     */
    @GetMapping("/reload")
    public Map<String, Object> reload() {
        return bossService.reloadBossData();
    }
}
