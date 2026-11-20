package com.getjobs.worker.boss;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BossFollowUpPolicyTest {
    @Test void supportsTwoDailyTimesAndASelectedWeekday() {
        var daily = new BossFollowUpPolicy(true,
                new BossFollowUpPolicy.Schedule("daily", List.of(), List.of("15:00", "10:00", "10:00")), null).validated();
        assertEquals(List.of("10:00", "15:00"), daily.schedule().times());
        assertNull(daily.slotAt(at("2026-11-20T09:59:59")));
        assertEquals(at("2026-11-20T10:00"), daily.slotAt(at("2026-11-20T10:00:52")));
        assertEquals(at("2026-11-20T15:00"), daily.slotAt(at("2026-11-20T15:00:10")));
        assertEquals(at("2026-11-21T10:00"), daily.nextAfter(at("2026-11-20T15:01")));

        var weekly = new BossFollowUpPolicy(true,
                new BossFollowUpPolicy.Schedule("weekly", List.of(1, 5), List.of("00:00", "23:59")), null).validated();
        assertEquals(at("2026-11-20T23:59"), weekly.slotAt(at("2026-11-20T23:59:30")));
        assertNull(weekly.slotAt(at("2026-11-21T00:00")));
        assertEquals(at("2026-11-23T00:00"), weekly.nextAfter(at("2026-11-20T23:59:30")));
        assertThrows(IllegalArgumentException.class, () -> new BossFollowUpPolicy.Schedule("daily", List.of(), List.of("24:00")));
    }

    @Test void queuedTaskUsesItsPlannedDayAndLegacyIntervalsStayDisabled() {
        var dates = new BossFollowUpPolicy.DateSelection(List.of(1), List.of("2026-10-11", "2026-11-19"));
        var policy = new BossFollowUpPolicy(false, null, dates);
        assertEquals(List.of("2026-10-11", "2026-11-19"),
                policy.window(at("2026-11-21T01:00"), at("2026-11-20T15:00")).dateLabels());
        var legacy = BossFollowUpPolicy.read("""
                {"enabled":true,"waitHours":48,"intervalHours":12,"batchSize":20,
                 "dateSelection":{"daysAgo":[1,2],"dates":["2026-11-08"]}}
                """);
        assertFalse(legacy.enabled());
        assertEquals(List.of(1, 2), legacy.dateSelection().daysAgo());
        assertEquals(List.of("2026-11-08"), legacy.dateSelection().dates());
        assertNull(legacy.nextAfter(at("2026-11-20T09:00")));
    }

    private static long at(String dateTime) {
        return LocalDateTime.parse(dateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
