package com.getjobs.worker.boss;

/** 当前岗位聊天区域不可操作；不据此认定岗位不匹配或账号额度用尽。 */
final class BossChatInteractionException extends RuntimeException {
    BossChatInteractionException(String message) { super(message); }
    BossChatInteractionException(String message,Throwable cause) { super(message,cause); }
}
