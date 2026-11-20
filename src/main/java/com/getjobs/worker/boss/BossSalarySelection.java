package com.getjobs.worker.boss;

import java.util.*;
import java.util.function.Function;

/** 期望月薪区间和对岗位标注薪资的额外上下限。 */
public record BossSalarySelection(List<Range> ranges, Double minPostedMonthlyK, Double maxPostedMonthlyK) {
    public record Range(Double minK, Double maxK) {
        boolean overlaps(double min, double max) {
            return (minK == null || max >= minK) && (maxK == null || min <= maxK);
        }
    }
    public BossSalarySelection { ranges = List.copyOf(ranges); }
    public BossSalarySelection(List<Range> ranges) { this(ranges, null, null); }
    public boolean unrestricted() { return ranges.isEmpty() && !hasPostedLimits(); }
    public boolean hasPostedLimits() { return minPostedMonthlyK != null || maxPostedMonthlyK != null; }
    public boolean overlaps(double min, double max) {
        return ranges.isEmpty() || ranges.stream().anyMatch(r -> r.overlaps(min,max));
    }
    public Double minimumK() {
        return ranges.isEmpty() || ranges.stream().anyMatch(r -> r.minK()==null) ? null
                : ranges.stream().mapToDouble(Range::minK).min().orElseThrow();
    }
    public Double maximumK() {
        return ranges.isEmpty() || ranges.stream().anyMatch(r -> r.maxK()==null) ? null
                : ranges.stream().mapToDouble(Range::maxK).max().orElseThrow();
    }
    public String describe() {
        if(ranges.isEmpty())return "不限";
        return ranges.stream().map(r -> r.minK()==null?number(r.maxK())+"K以下":r.maxK()==null?number(r.minK())+"K以上":number(r.minK())+"–"+number(r.maxK())+"K")
                .collect(java.util.stream.Collectors.joining("、"));
    }

    public String rejectionReason(String salary, BossSalary.Monthly monthly) {
        if (unrestricted()) return null;
        if (monthly == null) {
            return salary != null && salary.contains("面议")
                    ? "岗位薪资面议，无法确认符合月薪条件，已自动跳过"
                    : "补读详情后仍无法确认月薪符合设置，已自动跳过";
        }
        if (maxPostedMonthlyK != null && monthly.maxK() > maxPostedMonthlyK) {
            return "岗位最高月薪 " + number(monthly.maxK()) + "K 超过设置的 " + number(maxPostedMonthlyK) + "K（岗位 " + salary + "）";
        }
        if (minPostedMonthlyK != null && monthly.minK() < minPostedMonthlyK) {
            return "岗位最低月薪 " + number(monthly.minK()) + "K 低于设置的 " + number(minPostedMonthlyK) + "K（岗位 " + salary + "）";
        }
        return overlaps(monthly.minK(), monthly.maxK()) ? null
                : "薪资区间与设置无交集（岗位 " + salary + "，设置 " + describe() + "）";
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
