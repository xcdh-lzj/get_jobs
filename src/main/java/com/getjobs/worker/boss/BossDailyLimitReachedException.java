package com.getjobs.worker.boss;

/** 网站明确提示当天沟通额度用完；只结束当前账号，不代表用户停止整个队列。 */
public final class BossDailyLimitReachedException extends RuntimeException {
    public BossDailyLimitReachedException(String notice) {
        super("Boss 今日沟通已达上限：" + notice);
    }
}
