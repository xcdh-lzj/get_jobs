package com.getjobs.worker.manager;

import com.getjobs.application.service.BossAccountService;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 所有导航都由本地夹具响应，验证多账号监听与投递共用浏览器线程。 */
@EnabledIfEnvironmentVariable(named = "BOSS_CHAT_DOM_TEST", matches = "true")
class BossInboxServiceDomTest {
    @Test void fiveAccountsWatchIndependentlyWhileSearchUsesAnotherPage() throws Exception {
        var browserOptions = new BrowserType.LaunchOptions().setHeadless(true);
        String chrome = System.getenv("BOSS_TEST_CHROME");
        if (chrome != null) browserOptions.setExecutablePath(Path.of(chrome));
        try (var playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(browserOptions)) {
            var accounts = mock(BossAccountService.class);
            var browsers = mock(BossBrowserSessions.class);
            var manager = mock(PlaywrightManager.class);
            when(manager.tryRunOnPlaywright(any())).thenAnswer(call -> {
                call.<Runnable>getArgument(0).run();
                return true;
            });
            var userAccounts = new ArrayList<BossAccountService.Account>();
            var accountPages = new ArrayList<Page>();
            for (int i = 1; i <= 5; i++) {
                int count = i;
                var context = browser.newContext();
                context.route("**/*", route -> {
                    String url = route.request().url();
                    if (!url.startsWith("https://www.zhipin.com/web/")) {
                        route.abort();
                        return;
                    }
                    String content = BossInboxService.isChat(url) ? chat(count)
                            : url.contains("security-check") ? "<h1>安全验证</h1>" : "<h1>岗位搜索</h1>";
                    route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(content));
                });
                Page accountPage = context.newPage();
                accountPage.navigate(i == 1 ? "https://www.zhipin.com/web/geek/jobs" : BossInboxService.CHAT_URL);
                userAccounts.add(new BossAccountService.Account(i, "示例账号 " + i, i == 1));
                accountPages.add(accountPage);
                if (i < 5) {
                    when(browsers.loggedIn(i)).thenReturn(true);
                    when(browsers.isOpen(i)).thenReturn(true);
                    when(browsers.openedPage(i)).thenReturn(accountPage);
                } else {
                    when(browsers.ready(5, false)).thenAnswer(ignored -> {
                        when(browsers.loggedIn(5)).thenReturn(true);
                        when(browsers.isOpen(5)).thenReturn(true);
                        when(browsers.openedPage(5)).thenReturn(accountPage);
                        return accountPage;
                    });
                }
            }
            when(accounts.list()).thenReturn(userAccounts);
            var inbox = new BossInboxService(manager, browsers, accounts);
            assertFalse(inbox.snapshot().enabled());
            inbox.start(null);
            Page accountPage = accountPages.getFirst();
            Page search = PlaywrightUtil.newBackgroundPage(accountPage);
            search.navigate("https://www.zhipin.com/web/geek/jobs?query=fixture");
            search.waitForCondition(() -> {
                inbox.checkpoint();
                return inbox.snapshot().accounts().stream().allMatch(row -> "watching".equals(row.state()));
            }, new Page.WaitForConditionOptions().setTimeout(19_000));
            assertEquals(List.of(1, 2, 3, 0, 5), inbox.snapshot().accounts().stream()
                    .map(BossInboxService.AccountUnread::unread).toList());
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L), inbox.snapshot().selectedAccountIds());
            verify(browsers).ready(5, false);
            verify(browsers, never()).ready(1, false);
            assertTrue(inbox.snapshot().accounts().stream().allMatch(row -> row.notificationId() == 0));
            Page inboxPage = accountPage.context().pages().stream()
                    .filter(p -> BossInboxService.isChat(p.url())).findFirst().orElseThrow();
            assertTrue(inboxPage.locator("#unread-filter.selected").isVisible()); // 异步渲染的筛选栏
            for (int existing : List.of(1, 2, 4)) {
                assertTrue(accountPages.get(existing).locator("#unread-filter.selected").isVisible());
            }
            search.waitForTimeout(3_200);

            // BOSS 会在未读清空时卸载列表，也可能在列表重绘前先更新角标。
            Page cleared = accountPages.get(4);
            setFixtureFocus(cleared, false);
            accountPages.get(1).evaluate("""
                    () => {
                        document.querySelector('#unread').textContent = '3';
                        document.querySelector('.user-list-content').style.display = 'none';
                    }
                    """);
            accountPages.get(4).evaluate("""
                    () => {
                        document.querySelector('#unread').remove();
                        document.querySelector('.user-list-content').outerHTML = '<div class="chat-empty">暂无未读消息</div>';
                    }
                    """);
            search.waitForCondition(() -> Integer.valueOf(3).equals(row(inbox, 2).unread())
                    && Integer.valueOf(0).equals(row(inbox, 5).unread()));
            long notice = row(inbox, 2).notificationId();
            assertTrue(notice > 0);
            assertEquals(0, row(inbox, 5).notificationId());
            cleared.waitForFunction("() => window.refreshes === 2");
            cleared.evaluate("() => { document.querySelector('.chat-empty').textContent = '暂无未读会话' }");
            search.waitForTimeout(150);
            assertEquals(2, ((Number) cleared.evaluate("() => window.refreshes")).intValue());
            cleared.evaluate("""
                    () => {
                        const badge = document.createElement('i');
                        badge.className = 'badge';
                        badge.textContent = '1';
                        document.querySelector('li.selected').append(badge);
                    }
                    """);
            search.waitForCondition(() -> Integer.valueOf(1).equals(row(inbox, 5).unread()));
            cleared.evaluate("() => { document.querySelector('li.selected .badge').remove() }");
            cleared.waitForFunction("() => window.refreshes === 3");
            assertEquals(1, row(inbox, 1).unread());
            assertTrue(inbox.open(2, true).join().success());
            assertEquals(2, ((Number) accountPages.get(1).evaluate("() => window.refreshes")).intValue());
            assertEquals(notice, row(inbox, 2).notificationId());
            assertTrue(inbox.open(2, false).join().success());
            assertEquals(1, accountPages.get(1).context().pages().size());
            assertTrue(search.url().endsWith("query=fixture"));
            assertTrue(accountPage.url().endsWith("/jobs"));

            Page empty = accountPages.get(3);
            empty.evaluate("() => { document.querySelector('.chat-user').setAttribute('aria-busy', 'true') }");
            search.waitForCondition(() -> "unavailable".equals(row(inbox, 4).state()));
            assertNull(row(inbox, 4).unread());
            empty.evaluate("() => { document.querySelector('.chat-user').removeAttribute('aria-busy') }");
            search.waitForCondition(() -> Integer.valueOf(0).equals(row(inbox, 4).unread()));
            assertEquals(0, row(inbox, 4).notificationId());

            // 读最后一条时未读数即时归零，切出该标签页后才重新筛选。
            setFixtureFocus(inboxPage, true);
            inboxPage.locator("#last-thread").click();
            inboxPage.waitForCondition(() -> Integer.valueOf(0).equals(row(inbox, 1).unread()));
            inboxPage.waitForTimeout(2_200);
            assertTrue(inboxPage.locator("#opened-conversation").isVisible());
            assertEquals(1, ((Number) inboxPage.evaluate("() => window.refreshes")).intValue());
            setFixtureFocus(inboxPage, false);
            inboxPage.waitForFunction("() => window.refreshes === 2");
            assertTrue(inboxPage.locator("#opened-conversation").isHidden());

            // 新消息进入时，旧的归零刷新作废。避免离开页面时清理新的未读。
            setFixtureFocus(inboxPage, true);
            inboxPage.evaluate("() => document.querySelector('#unread-filter').insertAdjacentHTML('beforeend', '<i id=unread class=badge>1</i>')");
            inboxPage.waitForCondition(() -> Integer.valueOf(1).equals(row(inbox, 1).unread()));
            inboxPage.locator("#last-thread").click();
            inboxPage.waitForCondition(() -> Integer.valueOf(0).equals(row(inbox, 1).unread()));
            inboxPage.evaluate("() => document.querySelector('#unread-filter').insertAdjacentHTML('beforeend', '<i id=unread class=badge>2</i>')");
            inboxPage.waitForCondition(() -> Integer.valueOf(2).equals(row(inbox, 1).unread()));
            setFixtureFocus(inboxPage, false);
            search.waitForTimeout(150);
            assertEquals(2, ((Number) inboxPage.evaluate("() => window.refreshes")).intValue());

            // 关掉消息页后停止该账号的监听，等用户明确重新打开。
            inboxPage.close();
            search.waitForTimeout(2_100);
            inbox.schedule();
            assertEquals("closed", row(inbox, 1).state());
            assertEquals(2, accountPage.context().pages().size());
            assertTrue(inbox.open(1, false).join().success());
            assertEquals(3, accountPage.context().pages().size());

            Page blocked = accountPages.get(1);
            blocked.navigate("https://www.zhipin.com/web/common/security-check.html");
            assertEquals("unavailable", row(inbox, 2).state());
            assertNull(row(inbox, 2).unread());
            assertTrue(inbox.open(2, false).join().success());
            assertTrue(blocked.url().endsWith("security-check.html"));
            assertEquals(1, blocked.context().pages().size());

            // 监听中允许手动切到别的筛选，下一轮开始监听时再切回未读。
            Page existing = accountPages.get(2);
            existing.locator("#all-filter").click();
            search.waitForTimeout(2_100);
            inbox.schedule();
            assertTrue(existing.locator("#all-filter.selected").isVisible());
            inbox.stop();
            search.waitForTimeout(2_100);
            inbox.schedule();
            assertEquals("stopped", row(inbox, 1).state());
            assertFalse(search.isClosed());
            assertFalse(accountPages.get(3).isClosed());
            assertEquals("undefined", accountPages.get(3).evaluate("() => typeof window.__getJobsBossInbox"));

            inbox.start(List.of(3L));
            search.waitForCondition(() -> {
                inbox.checkpoint();
                return "watching".equals(row(inbox, 3).state());
            }, new Page.WaitForConditionOptions().setTimeout(8_000));
            assertTrue(existing.locator("#unread-filter.selected").isVisible());
            assertEquals(1, existing.context().pages().size());
        }
    }

    private static BossInboxService.AccountUnread row(BossInboxService inbox, int id) {
        return inbox.snapshot().accounts().get(id - 1);
    }

    private static void setFixtureFocus(Page page, boolean focused) {
        // 无窗口测试引擎不一定报告真实窗口焦点，在夹具中显式模拟。
        page.evaluate("""
                focused => {
                    Object.defineProperty(document, 'hidden', { configurable: true, value: false });
                    document.hasFocus = () => focused;
                    if (!focused) window.dispatchEvent(new Event('blur'));
                }
                """, focused);
    }

    private static String chat(int unread) {
        if (unread == 4) return """
                <div class="chat-user">
                  <div class="label-list" role="tablist">
                    <button role="tab">全部</button>
                    <button role="tab" aria-selected="true">未读消息</button>
                  </div>
                  <div class="chat-empty">暂无未读消息</div>
                </div>
                """;
        String content = """
                <div class="chat-user v2">
                  <div class="label-list"><ul>
                    <li id="all-filter" class="selected" onclick="document.querySelector('#unread-filter').classList.remove('selected'); this.classList.add('selected')">
                      全部 <i>50</i>
                    </li>
                    <li id="unread-filter" onclick="document.querySelector('#all-filter').classList.remove('selected'); this.classList.add('selected'); document.querySelector('#opened-conversation').hidden = true; window.refreshes = (window.refreshes || 0) + 1">
                      <span class="label-name">未读</span><i id="unread" class="badge">%d</i>
                    </li>
                    <li><span class="label-name">新招呼</span><i>12</i></li>
                  </ul></div>
                  <ul class="user-list-content">
                    <li id="last-thread" class="friend-content" role="listitem"
                      onclick="document.querySelector('#unread')?.remove(); document.querySelector('#opened-conversation').hidden = false">最后一条会话</li>
                    <li class="friend-content" role="listitem">这里也不是未读角标</li>
                  </ul>
                  <div id="opened-conversation" hidden>当前会话正文</div>
                </div>
                """.formatted(unread);
        if (unread != 1) return content;
        return "<template id='later'>" + content + "</template>"
                + "<script>setTimeout(() => document.body.append(document.querySelector('#later').content), 500)</script>";
    }
}
