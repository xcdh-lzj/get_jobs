package com.getjobs.worker.boss;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.getjobs.worker.boss.BossCityYieldMonitor.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class BossCityYieldMonitorTest {
    private BossCityYieldMonitor monitor(BossCityPolicy policy) {var m=new BossCityYieldMonitor(policy,List.of("A","B"),List.of("广州","深圳"));m.startCity(0);return m;}
    @Test void waitsForAFullWindowAndDoesNotTreatUncertainJobsAsEmptyInventory() {
        var m=monitor(new BossCityPolicy(true,5,0));for(int n=0;n<4;n++)m.record(UNAVAILABLE);assertFalse(m.lowYield());
        m.record(UNCERTAIN);assertFalse(m.lowYield());for(int n=0;n<5;n++)m.record(UNAVAILABLE);
        assertTrue(m.lowYield());assertTrue(m.endLowYieldKeyword());assertFalse(m.endLowYieldKeyword());
        assertEquals(1,m.snapshot().cities().getFirst().lowYieldKeywords());
    }
    @Test void thresholdUsesRecentJobsAndEveryKeywordStartsWithANewWindow() {
        var m=monitor(new BossCityPolicy(true,5,1));m.record(NEW_JOB);m.record(NEW_JOB);for(int n=0;n<3;n++)m.record(UNAVAILABLE);
        assertFalse(m.lowYield());m.record(UNAVAILABLE);assertTrue(m.lowYield());m.startKeyword();assertFalse(m.lowYield());
        assertEquals(0,m.snapshot().cities().getFirst().windowCount());
        m.record(NEW_JOB);assertEquals(3,m.snapshot().cities().getFirst().newJobs());
    }
    @Test void citiesHaveIndependentStatisticsAndStopOrQuotaCancelsRemainingCities() {
        var m=monitor(BossCityPolicy.defaults());m.record(NEW_JOB);m.completeCity(1);var before=m.snapshot();
        m.startCity(1);assertEquals(0,m.snapshot().cities().get(1).newJobs());assertEquals(1,before.cities().getFirst().newJobs());
        var stopped=monitor(BossCityPolicy.defaults());stopped.terminate("stopped");assertEquals("stopped",stopped.snapshot().cities().getFirst().state());assertEquals("cancelled",stopped.snapshot().cities().get(1).state());
        var limited=monitor(BossCityPolicy.defaults());limited.terminate("quota_reached");assertEquals("quota_reached",limited.snapshot().cities().getFirst().state());assertEquals("cancelled",limited.snapshot().cities().get(1).state());
    }
    @Test void disabledOrSingleCityDoesNotPrematurelyEndSearchAndInvalidThresholdsAreRejected() {
        var disabled=monitor(new BossCityPolicy(false,5,0));for(int n=0;n<8;n++)disabled.record(UNAVAILABLE);assertFalse(disabled.lowYield());
        var single=new BossCityYieldMonitor(new BossCityPolicy(true,5,0),List.of("A"),List.of("广州"));single.startCity(0);for(int n=0;n<8;n++)single.record(UNAVAILABLE);assertFalse(single.lowYield());
        assertThrows(IllegalArgumentException.class,()->new BossCityPolicy(true,4,0).validated());assertThrows(IllegalArgumentException.class,()->new BossCityPolicy(true,30,30).validated());
    }
}
