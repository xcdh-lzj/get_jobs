package com.getjobs.worker.boss.review;

/** 预算按供应商返回的用量结算；触发后不再发起新的审核或投递。 */
public final class BossReviewBudgetReachedException extends RuntimeException {
    public BossReviewBudgetReachedException(String message) { super(message); }
}
