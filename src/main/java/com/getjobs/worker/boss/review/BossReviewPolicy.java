package com.getjobs.worker.boss.review;

import java.util.List;

/** 精准审核独立于“AI 生成招呼语”开关。 */
public record BossReviewPolicy(boolean enabled, String goal, List<String> targetRoles, List<String> excludedTitles) {
    public static BossReviewPolicy defaults() {
        return new BossReviewPolicy(true,
                "主要从事 AI Agent 或大模型应用的软件研发，实际编写代码、开发后端服务、RAG、工具调用或工作流。排除产品规划、销售、售前、运营，以及只做基础模型预训练而不做应用研发的岗位。",
                List.of("AI Agent 智能体开发", "AI 大模型应用开发", "AI 应用开发工程师"),
                List.of("产品经理", "产品运营", "销售经理", "销售顾问", "销售代表", "销售工程师", "商务拓展", "售前顾问"));
    }
    public BossReviewPolicy validated() {
        if (goal == null || goal.isBlank() || goal.length() > 3000)
            throw new IllegalArgumentException("请填写不超过 3000 字的目标岗位职责");
        if (targetRoles == null || targetRoles.isEmpty() || targetRoles.size()>20
                || targetRoles.stream().anyMatch(s->s==null || s.isBlank() || s.length()>80))
            throw new IllegalArgumentException("请填写 1 至 20 个精投方向，每个不超过 80 字");
        if (excludedTitles == null || excludedTitles.size() > 50
                || excludedTitles.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 80))
            throw new IllegalArgumentException("岗位排除词最多 50 条，每条 1 至 80 字");
        return new BossReviewPolicy(enabled, goal.strip(), targetRoles.stream().map(String::strip).map(BossReviewPolicy::directionName).distinct().toList(),
                excludedTitles.stream().map(String::strip).distinct().toList());
    }
    /** 兼容上一版已保存的两个固定标识；新的方向均使用用户可编辑的名称。 */
    private static String directionName(String value) {
        return switch(value) {case "agent_development" -> "AI Agent 智能体开发";case "llm_application_development" -> "AI 大模型应用开发";default -> value;};
    }
    public BossReviewPolicy forceEnabled() { return new BossReviewPolicy(true, goal, targetRoles, excludedTitles); }
}
