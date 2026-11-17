package com.getjobs.worker.boss;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BossSalarySelectionTest {
    private BossSalarySelection selection(String raw) {return BossSalarySelection.fromStored(raw,code->Map.of("402","3K以下","403","3-5K","404","5-10K","405","10-20K","406","20-50K","407","50K以上").get(code));}
    @Test void selectedTenToTwentyIncludesLowOverlapsAndRejectsSalariesAboveTwenty() {
        var selected=selection("[405]");assertEquals(selection("[10-20K]"),selected);
        assertEquals(10.0,selected.minimumK());assertEquals(20.0,selected.maximumK());
        assertTrue(selected.overlaps(9,12));assertTrue(selected.overlaps(10,14));assertTrue(selected.overlaps(20,30));
        assertFalse(selected.overlaps(21,25));assertFalse(selected.overlaps(25,35));assertFalse(selected.overlaps(5,9));
    }
    @Test void multipleSelectionsRetainGapsAndOpenEndedBounds() {
        var separated=selection("[403,406]");assertTrue(separated.overlaps(3,4));assertTrue(separated.overlaps(22,30));assertFalse(separated.overlaps(12,18));
        var open=selection("[3K以下,50K以上]");assertNull(open.minimumK());assertNull(open.maximumK());assertFalse(open.unrestricted());
        assertTrue(open.overlaps(1,2));assertTrue(open.overlaps(60,70));assertFalse(open.overlaps(10,20));
    }
    @Test void emptyAndUnlimitedSelectionDoNotRetainHiddenSalaryLimitsAndInvalidCodesFailClearly() {
        for(String raw:Arrays.asList(null,"","[]","[0]","[不限]","[0,405]")){assertTrue(selection(raw).unrestricted());assertTrue(selection(raw).overlaps(1,100));}
        assertThrows(IllegalArgumentException.class,()->selection("[999]"));assertThrows(IllegalArgumentException.class,()->selection("[坏数据]"));
    }
    @Test void ordinaryWorkerUsesTheSameSelectionEvenWhenLegacyValuesDisagree() throws Exception {
        var worker=new Boss(null,null,null,null,null,null);var config=new BossConfig();
        config.setExpectedSalary(List.of(15,25));config.setSalarySelection(selection("[10-20K]"));worker.setConfig(config);
        var check=Boss.class.getDeclaredMethod("isSalaryNotExpected",String.class);check.setAccessible(true);
        assertEquals(false,check.invoke(worker,"9-12K"));assertEquals(true,check.invoke(worker,"21-25K"));
        config.setSalarySelection(selection("[3K以下,50K以上]"));assertEquals(true,check.invoke(worker,"10-20K"));assertEquals(false,check.invoke(worker,"60-70K"));
        config.setSalarySelection(selection("[不限]"));assertEquals(false,check.invoke(worker,"面议"));
    }
}
