package com.getjobs.worker.boss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 每个账号配置固定时刻和要核对的投递日期。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BossFollowUpPolicy(boolean enabled, Schedule schedule, DateSelection dateSelection) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final long MESSAGE_INTERVAL_MILLIS = Duration.ofHours(12).toMillis();

    public BossFollowUpPolicy {
        // 旧版间隔无法推断用户希望在哪个钟点发送，保留日期，等待用户设定时刻。
        if (schedule == null) {
            enabled = false;
            schedule = new Schedule("daily", List.of(), List.of());
        }
        if (dateSelection == null) dateSelection = new DateSelection(List.of(1), List.of());
    }

    public static BossFollowUpPolicy defaults() {
        return new BossFollowUpPolicy(false, null, null);
    }

    public record Schedule(String frequency, List<Integer> weekdays, List<String> times) {
        public Schedule {
            if (!"daily".equals(frequency) && !"weekly".equals(frequency))
                throw new IllegalArgumentException("请选择每天或每周执行");
            if (weekdays == null || weekdays.stream().anyMatch(day -> day == null || day < 1 || day > 7))
                throw new IllegalArgumentException("星期须在周一到周日之间");
            if (times == null || times.stream().anyMatch(time -> time == null || !time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")))
                throw new IllegalArgumentException("执行时间须为 24 小时制 HH:mm");
            weekdays = weekdays.stream().distinct().sorted().toList();
            times = times.stream().distinct().sorted().toList();
        }

        private boolean runsOn(LocalDate date) {
            return "daily".equals(frequency) || weekdays.contains(date.getDayOfWeek().getValue());
        }
    }

    /** 日期选择可合并滚动日期（如昨天）和固定的日历日期。 */
    public record DateSelection(List<Integer> daysAgo, List<String> dates) {
        public DateSelection {
            if (daysAgo == null || dates == null
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
            return !at.isAfter(cutoff) && dates.contains(at.toLocalDate());
        }

        public List<String> dateLabels() {
            return dates.stream().sorted().map(LocalDate::toString).toList();
        }
    }

    public Window window(long now) {
        return window(now, now);
    }

    public Window window(long now, long dateReference) {
        // 岗位历史使用服务端本地时间。等待中的任务以原定执行日计算「昨天」。
        var current = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault());
        var targetDay = Instant.ofEpochMilli(dateReference).atZone(current.getZone()).toLocalDate();
        return new Window(current, dateSelection.resolve(targetDay));
    }

    /** 每个本地日期的同一时刻只对应一个任务，跨夏令时重复小时也不会重复触发。 */
    public Long slotAt(long now) {
        if (!enabled) return null;
        var minute = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.MINUTES);
        if (!schedule.runsOn(minute.toLocalDate()) || !schedule.times().contains(minute.toLocalTime().toString())) return null;
        var slot = minute.toLocalDateTime().atZone(minute.getZone());
        return slot.equals(minute) ? slot.toInstant().toEpochMilli() : null;
    }

    public Long nextAfter(long now) {
        if (!enabled) return null;
        var current = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault());
        for (int offset = 0; offset <= 7; offset++) {
            var day = current.toLocalDate().plusDays(offset);
            if (!schedule.runsOn(day)) continue;
            for (String time : schedule.times()) {
                var local = day.atTime(LocalTime.parse(time));
                var slot = local.atZone(current.getZone());
                if (slot.toLocalDateTime().equals(local) && slot.isAfter(current)) return slot.toInstant().toEpochMilli();
            }
        }
        return null;
    }

    public BossFollowUpPolicy validated() {
        if (enabled && schedule.times().isEmpty()) {
            throw new IllegalArgumentException("请选择至少一个执行时间");
        }
        if (enabled && "weekly".equals(schedule.frequency()) && schedule.weekdays().isEmpty()) {
            throw new IllegalArgumentException("请选择每周执行的星期");
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
