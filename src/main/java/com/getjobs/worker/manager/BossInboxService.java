package com.getjobs.worker.manager;

import com.getjobs.application.service.BossAccountService;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 每个账号读取自己常驻消息页的角标。浏览器对象始终由 Playwright 线程访问。 */
@Slf4j
@Service
public class BossInboxService {
    static final String CHAT_URL = "https://www.zhipin.com/web/geek/chat";
    private static final String BINDING = "__getJobsReportBossUnread";
    private static final long STALE_MS = 150_000;
    private final PlaywrightManager manager;
    private final BossBrowserSessions browsers;
    private final BossAccountService accounts;
    private final String observerScript;
    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicLong notificationIds = new AtomicLong();
    private final ConcurrentHashMap<Long, Watch> watches = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Command> commands = new ConcurrentLinkedQueue<>();
    private volatile Session monitor = new Session(false, 0, List.of());
    private long nextReconcile;

    private record Session(boolean enabled, int generation, List<Long> accountIds) {
        boolean watches(long id) { return enabled && accountIds.contains(id); }
    }
    private record Command(long accountId, boolean refresh, CompletableFuture<Reply> reply) {}
    private record Reading(int generation, int unread, boolean capped, long at, long notificationId) {}

    private static final class Watch {
        Page page;
        final Set<Page> boundPages = Collections.newSetFromMap(new WeakHashMap<>());
        volatile int browserAttemptedGeneration = -1;
        volatile boolean opening;
        int attemptedGeneration = -1;
        int attachedGeneration = -1;
        volatile int presentedGeneration = -1;
        long attachedAt;
        volatile boolean closed;
        volatile boolean unavailable;
        volatile Reading reading;
    }

    public record AccountUnread(long accountId, String name, String state, boolean browserOpen, Integer unread,
                                boolean capped, Long updatedAt, long notificationId) {}
    public record Snapshot(String instanceId, boolean enabled, List<Long> selectedAccountIds, List<AccountUnread> accounts) {}
    public record Reply(boolean success, String message) {}

    public BossInboxService(PlaywrightManager manager, BossBrowserSessions browsers, BossAccountService accounts)
            throws IOException {
        this.manager = manager;
        this.browsers = browsers;
        this.accounts = accounts;
        observerScript = new ClassPathResource("boss/inbox-observer.js").getContentAsString(StandardCharsets.UTF_8);
    }

    public synchronized void start(List<Long> selection) {
        var available = accounts.list().stream().map(BossAccountService.Account::id).toList();
        var selected = selection == null ? available : selection;
        if (selected.isEmpty() || selected.stream().anyMatch(id -> id == null || !available.contains(id))
                || selected.stream().distinct().count() != selected.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一个有效的 Boss 账号");
        }
        if (!monitor.enabled() || !monitor.accountIds().equals(selected)) {
            monitor = new Session(true, monitor.generation() + 1, List.copyOf(selected));
        }
        schedule();
    }

    public synchronized void stop() {
        if (monitor.enabled()) monitor = new Session(false, monitor.generation() + 1, monitor.accountIds());
        schedule();
    }

    public Snapshot snapshot() {
        Session session = monitor;
        long now = System.currentTimeMillis();
        return new Snapshot(instanceId, session.enabled(), session.accountIds(), accounts.list().stream()
                .map(account -> row(account, session, now)).toList());
    }

    private AccountUnread row(BossAccountService.Account account, Session session, long now) {
        Watch watch = watches.get(account.id());
        boolean browserOpen = browsers.isOpen(account.id());
        boolean loggedIn = browsers.loggedIn(account.id());
        String state = "connecting";
        Reading measurement = watch == null ? null : watch.reading;
        if (!session.watches(account.id())) state = loggedIn ? "stopped" : "offline";
        else if (watch == null || watch.browserAttemptedGeneration != session.generation() || watch.opening) {
            state = browserOpen && (watch == null || !watch.opening) ? "connecting" : "opening";
        } else if (watch.unavailable) state = "unavailable";
        else if (!browserOpen) state = "closed";
        else if (!loggedIn) state = "offline";
        else if (watch.closed) state = "closed";
        else if (watch.presentedGeneration == session.generation() && measurement != null
                && measurement.generation() == session.generation() && measurement.unread() >= 0) {
            state = now - measurement.at() > STALE_MS ? "stale" : "watching";
            return new AccountUnread(account.id(), account.name(), state, browserOpen, measurement.unread(), measurement.capped(),
                    measurement.at(), "watching".equals(state) ? measurement.notificationId() : 0);
        }
        return new AccountUnread(account.id(), account.name(), state, browserOpen, null, false, null, 0);
    }

    public CompletableFuture<Reply> open(long id, boolean refresh) {
        if (!browsers.isOpen(id)) return CompletableFuture.completedFuture(new Reply(false, "请启动该账号的浏览器"));
        if (refresh && !monitor.watches(id)) return CompletableFuture.completedFuture(new Reply(false, "请先监听该账号"));
        var reply = new CompletableFuture<Reply>();
        reply.completeOnTimeout(new Reply(false, "浏览器暂未响应，请稍后再试"), 24, TimeUnit.SECONDS);
        commands.add(new Command(id, refresh, reply));
        schedule();
        return reply;
    }

    @Scheduled(fixedDelay = 2_000)
    public void schedule() {
        if (!monitor.enabled() && watches.isEmpty() && commands.isEmpty()) return;
        manager.tryRunOnPlaywright(() -> {
            checkpoint();
            // 没有投递任务时也需要派发浏览器送来的角标变化事件。
            watches.values().stream().filter(watch -> watch.attachedGeneration >= 0 && watch.page != null && !watch.page.isClosed())
                    .findFirst().ifPresent(watch -> watch.page.waitForTimeout(1));
        });
    }

    /** 可在投递或跟进的浏览器操作间隙调用，不接触任务的搜索、详情或聊天页。 */
    public void checkpoint() {
        if (!monitor.enabled() && watches.isEmpty() && commands.isEmpty()) return;
        Command command = commands.poll();
        if (command != null && !command.reply().isDone()) handle(command);
        long now = System.nanoTime();
        if (now < nextReconcile) return;
        nextReconcile = now + TimeUnit.SECONDS.toNanos(2);
        try {
            reconcile();
        } catch (RuntimeException error) {
            log.debug("消息监听检查暂不可用：{}", error.getClass().getSimpleName());
        }
    }

    private void reconcile() {
        Session session = monitor;
        for (var watchEntry : watches.entrySet()) {
            Watch watch = watchEntry.getValue();
            if (watch.attachedGeneration >= 0 && (!session.watches(watchEntry.getKey()) || !browsers.loggedIn(watchEntry.getKey()))) {
                detach(watch);
                watch.attemptedGeneration = -1;
            }
        }
        if (!session.enabled()) return;
        for (long id : session.accountIds()) {
            if (session != monitor) return;
            Watch watch = watches.computeIfAbsent(id, ignored -> new Watch());
            try {
                Page source = browsers.openedPage(id);
                if (watch.browserAttemptedGeneration != session.generation()) {
                    watch.unavailable = false;
                    if (source == null) {
                        watch.opening = true;
                        watch.browserAttemptedGeneration = session.generation();
                        try {
                            browsers.ready(id, false);
                        } finally {
                            watch.opening = false;
                        }
                        // 一次只打开一个浏览器，单个账号启动失败也可以继续监听其他账号。
                        break;
                    }
                    watch.browserAttemptedGeneration = session.generation();
                }
                if (source == null || !browsers.loggedIn(id)) continue;
                boolean needsPage = watch.page == null || watch.page.isClosed()
                        || watch.attemptedGeneration != session.generation()
                        && !isChat(watch.page.url()) && !needsVerification(watch.page.url());
                if (needsPage) {
                    if (watch.attemptedGeneration == session.generation()) continue;
                    if (needsVerification(source.url())) {
                        watch.unavailable = true;
                        continue;
                    }
                    detach(watch);
                    openPage(watch, source, session.generation());
                }
                if (isChat(watch.page.url())) {
                    attach(id, watch, session);
                    presentUnread(id, watch, session);
                } else {
                    watch.unavailable = true;
                }
                // 分开创建多个账号的页面，给投递浏览器留出操作间隙。
                if (needsPage) break;
            } catch (RuntimeException error) {
                watch.browserAttemptedGeneration = session.generation();
                watch.unavailable = true;
                log.debug("Boss 账号 {} 的消息页暂不可用：{}", id, error.getClass().getSimpleName());
            }
        }
    }

    private void presentUnread(long id, Watch watch, Session session) {
        if (session != monitor || watch.presentedGeneration == session.generation()) return;
        // 等消息页的筛选栏渲染完成。"开始监听"只激活一次各窗口内的消息标签页。
        if (!Boolean.TRUE.equals(watch.page.evaluate("() => window.__getJobsBossInbox?.openUnread()"))
                || session != monitor || !isChat(watch.page.url())) return;
        watch.page.bringToFront();
        watch.presentedGeneration = session.generation();
        log.info("Boss 账号 {} 已打开未读消息列表", id);
    }

    private void handle(Command command) {
        try {
            long id = command.accountId();
            Page source = browsers.openedPage(id);
            if (source == null || source.isClosed()) {
                command.reply().complete(new Reply(false, "该账号的浏览器已关闭或登录已失效"));
                return;
            }
            Session session = monitor;
            Watch watch = watches.computeIfAbsent(id, ignored -> new Watch());
            if (command.refresh()) {
                boolean refreshed = session.watches(id) && browsers.loggedIn(id) && watch.page != null && !watch.page.isClosed()
                        && isChat(watch.page.url())
                        && Boolean.TRUE.equals(watch.page.evaluate("() => window.__getJobsBossInbox?.refresh()"));
                command.reply().complete(refreshed ? new Reply(true, "已重新筛选 Boss 未读列表")
                        : new Reply(false, "未读列表暂不可用，请打开消息页确认"));
                return;
            }
            if (!browsers.loggedIn(id)) {
                if (!command.reply().isDone()) {
                    source.bringToFront();
                    command.reply().complete(new Reply(true, "已打开浏览器，请在 Boss 完成登录"));
                }
                return;
            }
            if (watch.page == null || watch.page.isClosed()
                    || !isChat(watch.page.url()) && !needsVerification(watch.page.url())) {
                detach(watch);
                openPage(watch, source, session.generation());
            }
            attach(id, watch, session);
            if (!command.reply().isDone()) {
                watch.page.bringToFront();
                command.reply().complete(new Reply(true, isChat(watch.page.url()) ? "已打开该账号的 Boss 消息页"
                        : "已打开该账号浏览器，请在 Boss 页面查看登录或验证状态"));
            }
        } catch (RuntimeException error) {
            log.debug("Boss 账号 {} 的消息页操作未完成：{}", command.accountId(), error.getClass().getSimpleName());
            command.reply().complete(new Reply(false, "消息页暂不可用，请检查该账号浏览器"));
        }
    }

    private void openPage(Watch watch, Page source, int generation) {
        watch.attemptedGeneration = generation;
        watch.page = isChat(source.url()) || needsVerification(source.url()) ? source : PlaywrightUtil.newBackgroundPage(source);
        watch.reading = null;
        watch.closed = false;
        watch.unavailable = false;
        Page opened = watch.page;
        opened.onClose(ignored -> {
            if (watch.page != opened) return;
            watch.closed = true;
            watch.unavailable = false;
            watch.reading = null;
        });
        if (opened != source) {
            opened.navigate(CHAT_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(8_000));
        }
        if (!isChat(opened.url())) watch.unavailable = true;
    }

    private void attach(long id, Watch watch, Session session) {
        Page page = watch.page;
        if (session != monitor || !session.watches(id) || !isChat(page.url()) || watch.attachedGeneration == session.generation()) return;
        if (!watch.boundPages.contains(page)) {
            page.exposeBinding(BINDING, (source, args) -> {
                if (watch.page == source.page() && source.frame() == page.mainFrame() && isChat(source.frame().url())) {
                    return report(id, watch, args);
                }
                return false;
            });
            page.onFrameNavigated(frame -> {
                if (watch.page != page || frame != page.mainFrame()) return;
                watch.attachedGeneration = -1;
                watch.reading = null;
                watch.unavailable = !isChat(frame.url());
            });
            watch.boundPages.add(page);
        }
        watch.reading = null;
        watch.attachedAt = System.currentTimeMillis();
        watch.attachedGeneration = session.generation();
        watch.unavailable = false;
        try {
            useNativeFocus(page);
            page.evaluate(observerScript, session.generation());
        } catch (RuntimeException error) {
            // 页面正在切换时留给下一轮重新挂接观察器。
            watch.attachedGeneration = -1;
            throw error;
        }
    }

    private static void useNativeFocus(Page page) {
        // Playwright 默认模拟页面始终有焦点。仅对消息页恢复真实焦点，才能在用户切离后刷新。
        try {
            var session = page.context().newCDPSession(page);
            try {
                var options = new JsonObject();
                options.addProperty("enabled", false);
                session.send("Emulation.setFocusEmulationEnabled", options);
            } finally {
                session.detach();
            }
        } catch (PlaywrightException error) {
            log.debug("无法获取消息页的真实焦点：{}", error.getClass().getSimpleName());
        }
    }

    private boolean report(long id, Watch watch, Object[] args) {
        if (args.length != 3 || !(args[0] instanceof Number generation) || !(args[1] instanceof Number count)
                || !(args[2] instanceof Boolean capped)) return false;
        Session session = monitor;
        int unread = count.intValue();
        if (!session.watches(id) || !browsers.loggedIn(id) || generation.doubleValue() != session.generation()
                || generation.intValue() != watch.attachedGeneration || count.doubleValue() != unread
                || unread < -1 || unread > 99_999) return false;
        long now = System.currentTimeMillis();
        Reading previous = watch.reading;
        long notification = 0;
        if (unread > 0 && previous != null && previous.generation() == session.generation() && previous.unread() >= 0
                && now - previous.at() <= STALE_MS && now - watch.attachedAt >= 3_000) {
            boolean increased = unread > previous.unread() || unread == previous.unread() && capped && !previous.capped();
            notification = increased ? notificationIds.incrementAndGet() : previous.notificationId();
        }
        watch.reading = new Reading(session.generation(), unread, capped, now, notification);
        watch.unavailable = unread < 0 && now - watch.attachedAt >= 3_000;
        return true;
    }

    private void detach(Watch watch) {
        if (watch.attachedGeneration >= 0 && watch.page != null && !watch.page.isClosed() && isChat(watch.page.url())) {
            try { watch.page.evaluate("() => window.__getJobsBossInbox?.stop()"); }
            catch (RuntimeException error) { log.debug("消息页已切换，观察器将随页面释放"); }
        }
        watch.attachedGeneration = -1;
        watch.reading = null;
    }

    static boolean isChat(String url) {
        return url != null && url.matches("https://www\\.zhipin\\.com/web/geek/chat/?(?:[?#].*)?");
    }

    private static boolean needsVerification(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost(), path = uri.getPath();
            return host != null && (host.equals("zhipin.com") || host.endsWith(".zhipin.com"))
                    && path != null && path.matches("(?i).*(?:/security(?:-check)?|/verify|/captcha|/login|/error/403).*");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
