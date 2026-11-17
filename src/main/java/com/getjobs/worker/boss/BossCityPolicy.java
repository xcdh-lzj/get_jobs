package com.getjobs.worker.boss;

/** 按已处理岗位判断近期产出，不用耗时或网络失败推测岗位存量。 */
public record BossCityPolicy(boolean enabled, int windowSize, int maxNewJobs) {
    public static BossCityPolicy defaults() { return new BossCityPolicy(true,30,1); }
    public BossCityPolicy validated() {
        if(windowSize<5 || windowSize>200)throw new IllegalArgumentException("低产出观察样本数须为 5 至 200");
        if(maxNewJobs<0 || maxNewJobs>=windowSize)throw new IllegalArgumentException("新增可投岗位阈值须为 0 至样本数减 1");
        return this;
    }
}
