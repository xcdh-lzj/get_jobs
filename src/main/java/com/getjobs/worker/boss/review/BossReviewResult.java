package com.getjobs.worker.boss.review;

import java.util.List;

public record BossReviewResult(BossReviewJob job, String decision, String category, String stage,
                               String reason, List<String> evidence) {
    public static BossReviewResult reject(BossReviewJob job, String reason, String evidence) {
        return new BossReviewResult(job,"reject","excluded","basic",reason,List.of(evidence));
    }
    public static BossReviewResult pending(BossReviewJob job, String reason) {
        return new BossReviewResult(job,"review","uncertain","semantic",reason,List.of());
    }
    public static BossReviewResult unconfirmed(BossReviewJob job, String reason) {
        return new BossReviewResult(job,"review","basic_unknown","basic",reason,List.of());
    }
    public boolean approved() { return "pass".equals(decision); }
}
