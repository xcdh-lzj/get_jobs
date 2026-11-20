package com.getjobs.worker.boss;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 每个账号独立授权定时跟进，旧版重复沟通开关不会启用定时发送。 */
public record BossFollowUpPolicy(boolean enabled, int waitHours, int intervalHours, int batchSize, DateSelection dateSelection) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final long MESSAGE_INTERVAL_MILLIS = Duration.ofHours(12).toMillis();

    public static BossFollowUpPolicy defaults() {
        return new BossFollowUpPolicy(false, 48, 24, 20, new DateSelection(List.of(1), List.of()));
    }

    /** 未设置日期的旧配置沿用等待时间。日期选择可合并相对日期和指定日历日期。 */
    public record DateSelection(List<Integer> daysAgo, List<String> dates) {
        public DateSelection {
            if (daysAgo == null || dates == null || daysAgo.size() > 31 || dates.size() > 31
                    || daysAgo.stream().anyMatch(day -> day == null || day < 0 || day > 30)) {
                throw new IllegalArgumentException("跟进日期超出选择范围");
            }
            daysAgo = daysAgo.stream().distinct().sorted().toList();
            dates = dates.stream().map(DateSelection::parseDate).distinct().sorted().toList();
            if (daysAgo.isEmpty() && dates.isEmpty()) throw new IllegalArgumentException("请至少选择一个跟进日期");
        }

        private static String parseDate(String date) {
            try {
                if (date != null && date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && LocalDate.parse(date).getYear() > 0) return date;
            } catch (DateTimeParseException ignored) {
                // 用统一的提示报告不可用日期。
            }
            throw new IllegalArgumentException("跟进日期须为有效的日历日期");
        }

        private Set<LocalDate> resolve(LocalDate today) {
            Set<LocalDate> selected = new LinkedHashSet<>();
            for (int day : daysAgo) selected.add(today.minusDays(day));
            for (String date : dates) selected.add(LocalDate.parse(date));
            return Set.copyOf(selected);
        }
    }

    public record Window(ZonedDateTime cutoff, Set<LocalDate> dates) {
        public boolean allows(long timestamp) {
            if (timestamp == 0) return true;
            var at = Instant.ofEpochMilli(timestamp).atZone(cutoff.getZone());
            return !at.isAfter(cutoff) && (dates.isEmpty() || dates.contains(at.toLocalDate()));
        }
    }

    public Window window(long now) {
        // 岗位历史在 SQLite 中使用服务端本地时间。
        var current = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault());
        return dateSelection == null ? new Window(current.minusHours(waitHours), Set.of())
                : new Window(current, dateSelection.resolve(current.toLocalDate()));
    }

    public BossFollowUpPolicy validated() {
        if (dateSelection == null && (waitHours < 1 || waitHours > 720)) {
            throw new IllegalArgumentException("首次联系后的等待时间须为 1 至 720 小时");
        }
        if (intervalHours < 1 || intervalHours > 720) {
            throw new IllegalArgumentException("自动检查间隔须为 1 至 720 小时");
        }
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("每轮检查上限须为 1 至 100 个岗位");
        }
        return this;
    }

    public static BossFollowUpPolicy read(String saved) {
        if (saved == null || saved.isBlank()) return defaults();
        try {
            return JSON.readValue(saved, BossFollowUpPolicy.class).validated();
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("定期跟进设置无法读取，请重新设置并保存", error);
        }
    }

    public String stored() {
        try {
            return JSON.writeValueAsString(validated());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法保存定期跟进设置", error);
        }
    }
}
