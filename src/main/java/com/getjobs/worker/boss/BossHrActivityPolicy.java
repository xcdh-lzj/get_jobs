package com.getjobs.worker.boss;

/** 只有能确认落在该范围内的 HR 活跃状态才放行。 */
public record BossHrActivityPolicy(int maxDays) {
    public static BossHrActivityPolicy defaults(){return new BossHrActivityPolicy(3);}
    public BossHrActivityPolicy validated(){
        if(maxDays<1 || maxDays>365)throw new IllegalArgumentException("HR 最近活跃范围须为 1 至 365 天");
        return this;
    }
}
