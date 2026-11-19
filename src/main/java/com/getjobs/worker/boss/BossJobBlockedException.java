package com.getjobs.worker.boss;

/** 平台明确提示当前岗位需要跳过，只结束该岗位的沟通流程。 */
final class BossJobBlockedException extends RuntimeException {
    BossJobBlockedException(String reason) {
        super(reason);
    }
}
