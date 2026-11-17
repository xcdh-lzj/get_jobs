package com.getjobs.worker.boss.review;

/** 仅岗位职责审核的已返回用量；不推算费用，不把缺失用量当成零消耗。 */
public record BossReviewUsage(boolean tracked, int requests, int failedRequests, int unreportedRequests,
                              long inputTokens, long outputTokens, long totalTokens,
                              Long reasoningTokens, Long cachedInputTokens, int reusedJobs,
                              int economyAppliedRequests, BossReviewEconomy settings) {}
