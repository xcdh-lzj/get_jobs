package com.getjobs.worker.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.BossAccountService;
import com.getjobs.application.service.CookieService;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 额外的 Boss 账号独占持久化会话；所有 API 仍在原来的 Playwright 专用线程执行。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BossBrowserSessions {
    private static final String ENTRY = "https://www.zhipin.com/web/geek/jobs";
    private static final Set<String> LOGIN_COOKIES = Set.of("bst","wt2","zp_at","geek_zp_token");
    private final PlaywrightManager manager;
    private final BossAccountService accounts;
    private final CookieService cookies;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private static final class Session {
        final BrowserContext context;
        final Page page;
        volatile boolean loggedIn;
        volatile String savedCookies = "";
        Session(BrowserContext context, Page page) { this.context=context; this.page=page; }
    }

    public Page ready(long id, boolean interactive) {
        accounts.require(id);
        if (id==1 && interactive && manager.isHeadless())
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "默认账号当前为无窗口模式。需要扫码时，请将 getjobs.browser.headless 改为 false 后重启；已有登录态可点击确认并保存登录。");
        manager.ensureReady();
        return manager.callOnPlaywright(() -> {
            if (id == 1) {
                Page page = manager.getBossPage();
                if (interactive) page.bringToFront(); // 仅用户主动打开登录页面时聚焦
                return page;
            }
            Session session = sessions.get(id);
            if (session == null || session.page.isClosed()) {
                if (session != null) { try { session.context.close(); } catch (Exception ignored) {} }
                BrowserContext context = manager.newBossAccountContext(profileDirectory(id), interactive);
                try {
                    String saved = accounts.cookies(id);
                    boolean persistentLogin=context.cookies("https://www.zhipin.com").stream().anyMatch(c -> LOGIN_COOKIES.contains(c.name));
                    if (!persistentLogin && saved != null && !saved.isBlank()) {
                        var restored = manager.filterCookiesByDomain(manager.parseCookiesFromString(saved),"zhipin.com");
                        if (!restored.isEmpty()) context.addCookies(restored);
                    }
                    Page page = context.pages().isEmpty() ? context.newPage() : context.pages().getFirst();
                    page.setDefaultTimeout(30_000);
                    page.navigate(ENTRY,new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000));
                    session = new Session(context,page);
                    sessions.put(id,session);
                    Session created = session;
                    context.onClose(ignored -> { created.loggedIn=false; sessions.remove(id,created); });
                } catch (Exception e) { context.close(); throw e; }
            }
            updateLogin(id,session);
            if (interactive) session.page.bringToFront();
            return session.page;
        });
    }

    public boolean loggedIn(long id) {
        if (id==1) return manager.isLoggedIn("boss");
        Session session=sessions.get(id);
        return session!=null && session.loggedIn;
    }
    public void save(long id) {
        manager.runOnPlaywright(() -> {
            if (id==1) manager.saveBossCookiesToDb("manual save");
            else { Session s=sessions.get(id); if (s!=null) updateLogin(id,s); }
        });
    }
    public void logout(long id) {
        manager.ensureReady();
        manager.runOnPlaywright(() -> {
            if (id==1) {
                manager.clearBossCookies();
                cookies.clearCookieByPlatform("boss","manual logout");
                manager.setLoginStatus("boss",false);
                Page page=manager.getBossPage();
                if (page!=null && !page.isClosed()) {
                    clearBossStorage(page);
                    page.navigate(ENTRY,new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000));
                }
            } else {
                Session session=sessions.remove(id);
                // 重启后尚未打开的账号也有持久化资料，不能只清数据库而留下磁盘登录态。
                BrowserContext context=session==null ? manager.newBossAccountContext(profileDirectory(id),false) : session.context;
                try {
                    if (session!=null) session.loggedIn=false;
                    context.clearCookies();
                    Page page=context.pages().isEmpty()?context.newPage():context.pages().getFirst();
                    clearBossStorage(page);
                } finally { context.close(); }
                accounts.saveCookies(id,"");
            }
        });
    }
    private void clearBossStorage(Page page) {
        var cdp=page.context().newCDPSession(page);
        try {
            for (String origin : new String[]{"https://www.zhipin.com","https://zhipin.com","https://login.zhipin.com","https://passport.zhipin.com"}) {
                var params=new com.google.gson.JsonObject();
                params.addProperty("origin",origin); params.addProperty("storageTypes","all");
                cdp.send("Storage.clearDataForOrigin",params);
            }
        } finally { cdp.detach(); }
    }
    static Path profileDirectory(long id) {
        if (id<2) throw new IllegalArgumentException("默认账号使用原浏览器目录");
        return Path.of(System.getProperty("user.dir"),"browser-accounts","boss",Long.toString(id)).toAbsolutePath();
    }
    private void updateLogin(long id, Session session) {
        try {
            var values=manager.filterCookiesByDomain(session.context.cookies(),"zhipin.com");
            session.loggedIn=values.stream().anyMatch(c -> LOGIN_COOKIES.contains(c.name) && c.value!=null && !c.value.isBlank()
                    && (c.expires<=0 || c.expires>System.currentTimeMillis()/1000.0));
            String json=new ObjectMapper().writeValueAsString(values);
            if (!json.equals(session.savedCookies)) { accounts.saveCookies(id,json); session.savedCookies=json; }
        } catch (Exception e) { session.loggedIn=false; log.debug("检查 Boss 账号 {} 登录状态失败",id); }
    }
    @Scheduled(fixedDelay=3000)
    public void monitor() {
        if (!sessions.isEmpty()) manager.tryRunOnPlaywright(() -> sessions.forEach(this::updateLogin));
    }
    @PreDestroy public void close() {
        if (!sessions.isEmpty()) manager.runOnPlaywright(() -> {
            sessions.values().forEach(s -> { try { s.context.close(); } catch (Exception ignored) {} });
            sessions.clear();
        });
    }
}
