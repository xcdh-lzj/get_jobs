package com.getjobs.worker.boss;

import com.getjobs.application.entity.BossOptionEntity;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BossSalaryPolicyTest {
    @Test void postedLimitsRequireBothEndpointsAndAnOverlapWithTheDesiredRange() {
        var policy = new BossSalaryPolicy("custom", 12.0, 18.0, 12.0, 20.0);
        var selection = policy.selection(null, code -> null);
        assertTrue(selection.overlaps(15, 60));
        assertTrue(selection.rejectionReason("15-60K", BossSalary.parse("15-60K")).contains("最高月薪 60K 超过设置的 20K"));
        assertTrue(selection.rejectionReason("8-15K", BossSalary.parse("8-15K")).contains("最低月薪 8K 低于设置的 12K"));
        assertTrue(selection.rejectionReason("19-20K", BossSalary.parse("19-20K")).startsWith("薪资区间与设置无交集"));
        assertNull(selection.rejectionReason("12-20K·13薪", BossSalary.parse("12-20K·13薪")));
        assertEquals(policy, BossSalaryPolicy.read(policy.stored()));

        var decimal = new BossSalaryPolicy("selection", null, null, null, 11.2).selection("[不限]", code -> null);
        assertNull(decimal.rejectionReason("1.12万", BossSalary.parse("1.12万")));
        assertNotNull(decimal.rejectionReason("11.21K", BossSalary.parse("11.21K")));
        assertFalse(decimal.unrestricted());
        assertNull(decimal.maximumK());
    }

    @Test void customMonthlyBoundsSupportDecimalsAndClearingEitherSide() {
        var policy=new BossSalaryPolicy("custom",12.5,18.5).validated();
        var chosen=policy.selection("[50K以上]",code->null);
        assertTrue(chosen.overlaps(12,13));assertFalse(chosen.overlaps(10,12.4));assertFalse(chosen.overlaps(18.6,20));
        assertEquals(policy,BossSalaryPolicy.read(policy.stored()));
        assertTrue(new BossSalaryPolicy("custom",15.0,null).selection(null,code->null).overlaps(60,70));
        assertTrue(new BossSalaryPolicy("custom",null,20.0).selection(null,code->null).overlaps(5,8));
        assertTrue(new BossSalaryPolicy("custom",null,null).selection(null,code->null).unrestricted());
    }
    @Test void invalidCustomSettingsAreRejectedBeforeSaving() {
        for(var policy:List.of(new BossSalaryPolicy("custom",20.0,10.0),new BossSalaryPolicy("custom",0.0,null),new BossSalaryPolicy("custom",Double.NaN,null),new BossSalaryPolicy("custom",null,Double.POSITIVE_INFINITY),new BossSalaryPolicy("other",10.0,20.0)))
            assertThrows(IllegalArgumentException.class,policy::validated);
        assertEquals(BossSalaryPolicy.defaults(),BossSalaryPolicy.read(null));
        assertEquals(BossSalaryPolicy.defaults(),new BossSalaryPolicy("selection",15.0,25.0).validated());
        assertEquals(BossSalaryPolicy.defaults(), BossSalaryPolicy.read("{\"mode\":\"selection\"}"));
        for (var policy : List.of(new BossSalaryPolicy("selection", null, null, 30.0, 20.0),
                new BossSalaryPolicy("custom", null, null, 0.0, 20.0),
                new BossSalaryPolicy("selection", null, null, null, Double.NaN))) {
            assertThrows(IllegalArgumentException.class, policy::validated);
        }
    }
    @Test void websiteBucketsCoverTheCustomRangeInsteadOfClippingItToTheOldSelection() {
        var names=List.of("不限","3K以下","3-5K","5-10K","10-20K","20-50K","50K以上");
        var codes=List.of("0","402","403","404","405","406","407");var options=new ArrayList<BossOptionEntity>();
        for(int i=0;i<names.size();i++){var option=new BossOptionEntity();option.setName(names.get(i));option.setCode(codes.get(i));options.add(option);}
        assertEquals(List.of("405","406"),new BossSalaryPolicy("custom",15.0,25.0).searchCodes(options));
        assertEquals(List.of("405"),new BossSalaryPolicy("custom",12.5,18.5).searchCodes(options));
        assertEquals(List.of("407"),new BossSalaryPolicy("custom",60.0,null).searchCodes(options));
        assertEquals(List.of("0"),new BossSalaryPolicy("custom",null,null).searchCodes(options));
        assertEquals(List.of("405"), new BossSalaryPolicy("custom", 12.0, 18.0, 12.0, 18.0).searchCodes(options));
        assertEquals(List.of("0"), new BossSalaryPolicy("custom", null, null, 12.0, 18.0).searchCodes(options));
    }
}
