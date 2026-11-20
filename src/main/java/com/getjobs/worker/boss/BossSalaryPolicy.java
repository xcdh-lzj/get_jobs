package com.getjobs.worker.boss;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.application.entity.BossOptionEntity;
import java.util.*;
import java.util.function.Function;

/** 当前账号明确可见的月薪过滤设置；旧的隐藏上下限不再作为设置来源。 */
public record BossSalaryPolicy(String mode, Double minMonthlyK, Double maxMonthlyK,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Double minPostedMonthlyK,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Double maxPostedMonthlyK) {
    private static final ObjectMapper JSON=new ObjectMapper();

    public BossSalaryPolicy(String mode, Double minMonthlyK, Double maxMonthlyK) {
        this(mode, minMonthlyK, maxMonthlyK, null, null);
    }

    public static BossSalaryPolicy defaults(){return new BossSalaryPolicy("selection",null,null);}
    public BossSalaryPolicy validated() {
        if(!Set.of("selection","custom").contains(mode==null?"":mode))throw new IllegalArgumentException("请选择薪资过滤方式");
        if (invalidAmount(minPostedMonthlyK) || invalidAmount(maxPostedMonthlyK))
            throw new IllegalArgumentException("岗位薪资上下限须为大于 0 的数值，单位 K/月");
        if (minPostedMonthlyK != null && maxPostedMonthlyK != null && minPostedMonthlyK > maxPostedMonthlyK)
            throw new IllegalArgumentException("岗位最低月薪的限制不能高于最高月薪的限制");
        if ("selection".equals(mode)) return new BossSalaryPolicy(mode, null, null, minPostedMonthlyK, maxPostedMonthlyK);
        if (invalidAmount(minMonthlyK) || invalidAmount(maxMonthlyK))
            throw new IllegalArgumentException("自定义月薪须为大于 0 的数值，单位 K/月；留空表示不限制该边界");
        if(minMonthlyK!=null && maxMonthlyK!=null && minMonthlyK>maxMonthlyK)throw new IllegalArgumentException("最低月薪不能高于最高月薪");
        return this;
    }

    private static boolean invalidAmount(Double value) {
        return value != null && (!Double.isFinite(value) || value <= 0 || value > 1_000_000);
    }

    public BossSalarySelection selection(String saved,Function<String,String> nameForCode) {
        var policy = validated();
        List<BossSalarySelection.Range> ranges = "selection".equals(policy.mode())
                ? BossSalarySelection.fromStored(saved, nameForCode).ranges()
                : minMonthlyK == null && maxMonthlyK == null
                    ? List.of() : List.of(new BossSalarySelection.Range(minMonthlyK, maxMonthlyK));
        return new BossSalarySelection(ranges, policy.minPostedMonthlyK(), policy.maxPostedMonthlyK());
    }
    public List<String> searchCodes(List<BossOptionEntity> options) {
        var selected=selection(null,code->null);
        if(selected.ranges().isEmpty())return List.of("0");
        var codes=new LinkedHashSet<String>();
        for(var option:options) {
            if(option.getCode()==null || "0".equals(option.getCode()) || "不限".equals(option.getName()))continue;
            var bucket=BossSalarySelection.fromStored(option.getName(),code->null);
            for(var range:bucket.ranges())if(selected.overlaps(range.minK()==null?0:range.minK(),range.maxK()==null?Double.POSITIVE_INFINITY:range.maxK()))codes.add(option.getCode());
        }
        return codes.isEmpty()?List.of("0"):List.copyOf(codes);
    }
    public static BossSalaryPolicy of(BossConfigEntity config) {
        if(config==null)return defaults();
        return config.getSalaryPolicy()!=null?config.getSalaryPolicy().validated():read(config.getSalaryFilterPolicy());
    }
    public static BossSalaryPolicy read(String stored) {
        if(stored==null || stored.isBlank())return defaults();
        try{return JSON.readValue(stored,BossSalaryPolicy.class).validated();}
        catch(Exception error){throw new IllegalArgumentException("薪资过滤设置无法读取，请重新设置并保存",error);}
    }
    public String stored(){try{return JSON.writeValueAsString(validated());}catch(Exception error){throw new IllegalArgumentException("薪资过滤设置无法保存",error);}}
}
