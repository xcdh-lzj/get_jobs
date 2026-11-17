package com.getjobs.worker.boss.review;

/** 省钱设置与求职方向分别保存，不改变用户的职业目标。 */
public record BossReviewEconomy(boolean enabled, int cacheDays, long taskTokenLimit) {
    public static BossReviewEconomy defaults() { return new BossReviewEconomy(true, 7, 0); }

    public BossReviewEconomy validated() {
        if (cacheDays < 1 || cacheDays > 30)
            throw new IllegalArgumentException("审核缓存有效期须为 1 至 30 天");
        if (taskTokenLimit < 0 || taskTokenLimit > 10_000_000L)
            throw new IllegalArgumentException("单次任务 token 预算须为 0 至 10000000，0 表示不限制");
        return this;
    }
}
