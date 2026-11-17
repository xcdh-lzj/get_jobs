package com.getjobs.worker.boss;

import java.util.*;
import java.util.function.Function;

/** 由可见薪资设置派生的月薪区间；多选保留各段区间，不能把中间未选范围也放行。 */
public record BossSalarySelection(List<Range> ranges) {
    public record Range(Double minK, Double maxK) {
        boolean overlaps(double min, double max) {
            return (minK == null || max >= minK) && (maxK == null || min <= maxK);
        }
    }
    public BossSalarySelection { ranges = List.copyOf(ranges); }
    public boolean unrestricted() { return ranges.isEmpty(); }
    public boolean overlaps(double min, double max) {
        return unrestricted() || ranges.stream().anyMatch(r -> r.overlaps(min,max));
    }
    public Double minimumK() {
        return unrestricted() || ranges.stream().anyMatch(r -> r.minK()==null) ? null
                : ranges.stream().mapToDouble(Range::minK).min().orElseThrow();
    }
    public Double maximumK() {
        return unrestricted() || ranges.stream().anyMatch(r -> r.maxK()==null) ? null
                : ranges.stream().mapToDouble(Range::maxK).max().orElseThrow();
    }
    public String describe() {
        if(unrestricted())return "不限";
        return ranges.stream().map(r -> r.minK()==null?number(r.maxK())+"K以下":r.maxK()==null?number(r.minK())+"K以上":number(r.minK())+"–"+number(r.maxK())+"K")
                .collect(java.util.stream.Collectors.joining("、"));
    }
    private static String number(Double value){return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
    public static BossSalarySelection fromStored(String raw, Function<String,String> nameForCode) {
        if(raw==null || raw.isBlank())return new BossSalarySelection(List.of());
        String text=raw.strip();
        if(text.startsWith("[") && text.endsWith("]"))text=text.substring(1,text.length()-1);
        var names=new LinkedHashSet<String>();
        for(String item:text.split("[,，]")) {
            String value=item.strip().replaceAll("^\"|\"$","");
            if(value.isBlank())continue;
            if(value.equals("0") || value.equals("不限"))return new BossSalarySelection(List.of());
            if(value.matches("[0-9]+"))value=nameForCode.apply(value);
            if(value==null || value.isBlank())throw new IllegalArgumentException("无法识别薪资档位，请重新选择并保存");
            names.add(BossSalary.normalize(value));
        }
        if(names.contains("不限"))return new BossSalarySelection(List.of());
        var ranges=new ArrayList<Range>();
        for(String name:names) {
            boolean below=name.endsWith("以下"),above=name.endsWith("以上");
            String amount=below || above ? name.substring(0,name.length()-2):name;
            var monthly=BossSalary.parse(amount);
            if(monthly==null || ((below || above) && monthly.minK()!=monthly.maxK()))
                throw new IllegalArgumentException("无法识别薪资档位“"+name+"”，请重新选择并保存");
            ranges.add(new Range(below?null:monthly.minK(),above?null:monthly.maxK()));
        }
        return new BossSalarySelection(ranges);
    }
}
