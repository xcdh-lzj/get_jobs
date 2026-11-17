package com.getjobs.application.service;

/** Boss 的账号范围只在一次请求或一次任务内有效，不使用全局“当前账号”。 */
public final class BossAccountContext implements AutoCloseable {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private final Long previous;

    private BossAccountContext(long accountId) {
        if (accountId < 1) throw new IllegalArgumentException("账号 ID 无效");
        previous = CURRENT.get();
        CURRENT.set(accountId);
    }

    public static long id() { return CURRENT.get() == null ? 1L : CURRENT.get(); }
    public static BossAccountContext use(long accountId) { return new BossAccountContext(accountId); }
    public static void clear() { CURRENT.remove(); }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
