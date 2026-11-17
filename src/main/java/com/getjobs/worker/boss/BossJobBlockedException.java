package com.getjobs.worker.boss;

/** 平台明确阻止当前岗位沟通；只跳过当前岗位，不结束账号或队列。 */
final class BossJobBlockedException extends RuntimeException {
    BossJobBlockedException(String reason) {
        super(reason);
    }
}
