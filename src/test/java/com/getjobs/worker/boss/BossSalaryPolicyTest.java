package com.getjobs.worker.boss;

import com.getjobs.application.entity.BossOptionEntity;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BossSalaryPolicyTest {
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
    }
    @Test void websiteBucketsCoverTheCustomRangeInsteadOfClippingItToTheOldSelection() {
        var names=List.of("不限","3K以下","3-5K","5-10K","10-20K","20-50K","50K以上");
        var codes=List.of("0","402","403","404","405","406","407");var options=new ArrayList<BossOptionEntity>();
        for(int i=0;i<names.size();i++){var option=new BossOptionEntity();option.setName(names.get(i));option.setCode(codes.get(i));options.add(option);}
        assertEquals(List.of("405","406"),new BossSalaryPolicy("custom",15.0,25.0).searchCodes(options));
        assertEquals(List.of("405"),new BossSalaryPolicy("custom",12.5,18.5).searchCodes(options));
        assertEquals(List.of("407"),new BossSalaryPolicy("custom",60.0,null).searchCodes(options));
        assertEquals(List.of("0"),new BossSalaryPolicy("custom",null,null).searchCodes(options));
    }
}
