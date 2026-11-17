package com.getjobs.worker.boss;

import com.microsoft.playwright.*;
import com.getjobs.worker.utils.PlaywrightUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 仅使用路由拦截的模拟网页，不连接招聘网站，也不发送任何消息。 */
@EnabledIfEnvironmentVariable(named = "BOSS_CHAT_DOM_TEST", matches = "true")
class BossChatNavigatorDomTest {
    static Playwright playwright;
    static Browser browser;
    BrowserContext context;
    Page page;

    @BeforeAll static void launch() {
        var environment = new java.util.HashMap<>(System.getenv());
        environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(environment));
        String chrome = System.getenv("BOSS_TEST_CHROME");
        // Patchright 不包含 Playwright 默认的此开关；隔离模拟站点需允许延迟弹窗，避免浏览器策略干扰导航测试。
        var options = new BrowserType.LaunchOptions().setHeadless(true).setArgs(java.util.List.of("--disable-popup-blocking"));
        if (chrome != null) options.setExecutablePath(Path.of(chrome));
        browser = playwright.chromium().launch(options);
    }
    @AfterAll static void shutdown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }
    @BeforeEach void setup() { context = browser.newContext(); page = context.newPage(); }
    @AfterEach void cleanup() { context.close(); }

    @Test void backgroundDetailPageCanNavigateAndReceiveInput() {
        context.route("http://getjobs.test/background", route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("text/html")
                .setBody("<input id='message'><button onclick=\"document.title=document.querySelector('#message').value\">Check</button>")));
        Page detail = PlaywrightUtil.newBackgroundPage(page);
        try {
            assertSame(context, detail.context());
            assertNotSame(page, detail);
            detail.navigate("http://getjobs.test/background");
            detail.locator("#message").fill("local fixture only");
            detail.locator("button").click();
            assertEquals("local fixture only", detail.title());
        } finally {
            detail.close();
        }
        assertFalse(page.isClosed());
    }

    @Test void backgroundPageWorksInPersistentContextAndRetainsLogin(@TempDir Path profile) {
        var options = new BrowserType.LaunchPersistentContextOptions().setHeadless(true);
        String chrome = System.getenv("BOSS_TEST_CHROME");
        if (chrome != null) options.setExecutablePath(Path.of(chrome));
        try (BrowserContext first = playwright.chromium().launchPersistentContext(profile, options)) {
            first.addCookies(java.util.List.of(new com.microsoft.playwright.options.Cookie("fixture-session", "local-only")
                    .setDomain("getjobs.test").setPath("/")
                    .setExpires(System.currentTimeMillis() / 1000.0 + 3600)));
        }
        try (BrowserContext restored = playwright.chromium().launchPersistentContext(profile, options)) {
            Page source = restored.pages().getFirst();
            Page detail = PlaywrightUtil.newBackgroundPage(source);
            try {
                assertSame(restored, detail.context());
                assertTrue(restored.cookies("http://getjobs.test/").stream().anyMatch(cookie ->
                        "fixture-session".equals(cookie.name) && "local-only".equals(cookie.value)));
                detail.setContent("<title>Background persistent fixture</title>");
                assertEquals("Background persistent fixture", detail.title());
            } finally {
                detail.close();
            }
            assertFalse(source.isClosed());
        }
    }

    @Test void accountsKeepIndependentPersistentCookiesAndBossLogoutPreservesOtherPlatforms(@TempDir Path profiles) {
        var options=new BrowserType.LaunchPersistentContextOptions().setHeadless(true);
        String chrome=System.getenv("BOSS_TEST_CHROME");
        if (chrome!=null) options.setExecutablePath(Path.of(chrome));
        double expires=System.currentTimeMillis()/1000.0+3600;
        try (BrowserContext first=playwright.chromium().launchPersistentContext(profiles.resolve("a"),options);
             BrowserContext second=playwright.chromium().launchPersistentContext(profiles.resolve("b"),options)) {
            first.addCookies(java.util.List.of(
                    new com.microsoft.playwright.options.Cookie("wt2","account-a").setDomain(".zhipin.com").setPath("/").setExpires(expires),
                    new com.microsoft.playwright.options.Cookie("other-login","liepin-fixture").setDomain(".liepin.com").setPath("/").setExpires(expires)));
            second.addCookies(java.util.List.of(new com.microsoft.playwright.options.Cookie("wt2","account-b")
                    .setDomain(".zhipin.com").setPath("/").setExpires(expires)));
            var manager=new com.getjobs.worker.manager.PlaywrightManager();
            org.springframework.test.util.ReflectionTestUtils.setField(manager,"context",first);
            manager.clearBossCookies();
            assertTrue(first.cookies("https://www.zhipin.com").isEmpty());
            assertTrue(first.cookies("https://www.liepin.com").stream().anyMatch(c -> "liepin-fixture".equals(c.value)));
            assertTrue(second.cookies("https://www.zhipin.com").stream().anyMatch(c -> "account-b".equals(c.value)));
        }
        try (BrowserContext restored=playwright.chromium().launchPersistentContext(profiles.resolve("b"),options)) {
            assertTrue(restored.cookies("https://www.zhipin.com").stream().anyMatch(c -> "account-b".equals(c.value)));
        }
    }

    void fixture(boolean popup, boolean showModal, boolean navigate, boolean showInput) {
        String target = popup ? "window.fixtureOpenAttempts=(window.fixtureOpenAttempts||0)+1;window.fixturePopup=window.open('/chat', '_blank')" : "location.href='/chat'";
        String go = navigate ? "setTimeout(()=>{" + target + "},200)" : "";
        String modal = """
                <div id="confirm-dialog" class="greet-boss-dialog">
                  <div class="greet-boss-layer"></div><div class="greet-boss-container">
                  <div class="greet-boss-header"><h3>已向BOSS发送消息</h3></div>
                  <div class="greet-boss-body"><p class="greet-con">测试消息</p></div>
                  <div class="greet-boss-footer"><a href="javascript:;" class="default-btn cancel-btn">留在此页</a>
                    <a href="javascript:;" class="default-btn sure-btn" id="continue" onclick="document.querySelector('#confirm-dialog').remove();%s">继续沟通</a>
                  </div></div>
                </div>
                """.formatted(go);
        // 弹框确认后，原页面下方也有可见输入框；必须等新聊天页，不能使用它。
        String onclick = showModal
                ? "setTimeout(()=>{document.querySelector('#modal').innerHTML=" + quote(modal)
                    + ";document.querySelector('#old-input').style.display='block'},100)"
                : go;
        String job = "<button id='job-button' onclick=\"" + onclick.replace("&", "&amp;").replace("\"", "&quot;")
                + "\">继续沟通</button><div id='modal'></div>"
                + "<textarea class='input-area' id='old-input' style='display:none'></textarea>";
        String chat = showInput
                ? "<p>新的聊天页面</p><div id='mount'></div><script>setTimeout(()=>{document.querySelector('#mount').innerHTML=\"<textarea class='input-area' id='new-input'></textarea>\"},150)</script>"
                : "<p>聊天页还没加载出输入框</p>";
        context.route("http://getjobs.test/**", route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("text/html; charset=utf-8")
                .setBody(route.request().url().endsWith("/chat") ? chat : job)));
        page.navigate("http://getjobs.test/job");
    }

    static String quote(String text) {
        return org.json.JSONObject.quote(text);
    }

    @Test void firstContactCanStillUseInlineChatWithoutNavigation() {
        page.setContent("<button id='start' onclick=\"document.querySelector('#mount').innerHTML=&#34;&lt;textarea class='input-area'&gt;&lt;/textarea&gt;&#34;\">立即沟通</button><div id='mount'></div>");
        try (var navigator = new BossChatNavigator(page)) {
            var chat = navigator.open(page.locator("#start"), false, false, null, 3_000);
            assertNotNull(chat);
            assertFalse(chat.repeat());
            assertEquals(page, chat.page());
        }
    }

    @ParameterizedTest
    @CsvSource({"150,true", "100,false", "200,false"})
    void dailyLimitDialogEndsAccountWithoutAcceptingUnderlyingChatInput(int count, boolean title) {
        page.setContent("""
                <button id="start" onclick="document.querySelector('#limit').style.display='block'">立即沟通</button>
                <div id="limit" style="display:none">%s
                  <p>您今天已与%d位BOSS沟通，休息一下，明天再来吧～</p>
                  <button onclick="window.quotaConfirmed=true">确定</button>
                </div><textarea class="input-area"></textarea>
                """.formatted(title?"<h3>您已达到沟通上限</h3>":"",count));
        try (var navigator=new BossChatNavigator(page)) {
            assertThrows(BossDailyLimitReachedException.class,
                    () -> navigator.open(page.locator("#start"),false,false,null,3_000));
            assertNotEquals(true,page.evaluate("() => window.quotaConfirmed === true"));
        }
        assertTrue(context.pages().isEmpty());
    }

    @Test void quotaDetectionContinuesAfterTheOneSecondConfirmationWindow() {
        page.setContent("""
                <button id="start" onclick="setTimeout(()=>{document.querySelector('#limit').style.display='block'},1200)">立即沟通</button>
                <div id="limit" style="display:none"><h3>您已达到沟通上限</h3></div>
                """);
        try (var navigator=new BossChatNavigator(page)) {
            assertThrows(BossDailyLimitReachedException.class,
                    () -> navigator.open(page.locator("#start"),false,false,null,3_000));
        }
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void recognizesLimitOnOriginalPageOrNewPopupAndClosesOnlyOwnedPages(boolean onOrigin) {
        context.route("http://getjobs.test/limit-popup",route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("text/html; charset=utf-8")
                .setBody(onOrigin?"<textarea class='input-area'></textarea>":"<h3>您已达到沟通上限</h3>")));
        page.setContent("""
                <button id="start" onclick="document.querySelector('#limit').style.display='block';window.open('http://getjobs.test/limit-popup')">立即沟通</button>
                <div id="limit" style="display:none">%s</div>
                """.formatted(onOrigin?"<h3>您已达到沟通上限</h3>":""));
        Page unrelated=context.newPage();
        try (var navigator=new BossChatNavigator(page)) {
            assertThrows(BossDailyLimitReachedException.class,
                    () -> navigator.open(page.locator("#start"),false,false,null,3_000));
        }
        assertFalse(unrelated.isClosed());
        assertEquals(1,context.pages().size());
    }

    @Test void hiddenQuotaNoticeAndUnrelatedLimitsDoNotEndAReadyChat() {
        page.setContent("""
                <div style="display:none"><h3>您已达到沟通上限</h3></div>
                <p>薪资上限150K，职位收藏已达上限</p>
                <button id="start" onclick="document.querySelector('#input').style.display='block'">立即沟通</button>
                <textarea class="input-area" id="input" style="display:none"></textarea>
                """);
        try (var navigator=new BossChatNavigator(page)) {
            assertNotNull(navigator.open(page.locator("#start"),false,false,null,3_000));
        }
    }

    @Test void alreadyVisibleQuotaPreventsClickingCommunicationAgain() {
        page.setContent("<h3>您已达到沟通上限</h3><button id='start' onclick='window.attempted=true'>立即沟通</button>");
        try (var navigator=new BossChatNavigator(page)) {
            assertThrows(BossDailyLimitReachedException.class,
                    () -> navigator.open(page.locator("#start"),false,false,null,3_000));
            assertNotEquals(true,page.evaluate("() => window.attempted === true"));
        }
    }

    @Test void lateLimitOnOriginIsCheckedAgainBeforeUsingThePopupToSend() {
        context.route("http://getjobs.test/ready-popup",route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("text/html; charset=utf-8").setBody("<textarea class='input-area'></textarea>")));
        page.setContent("""
                <button id="start" onclick="window.open('http://getjobs.test/ready-popup')">立即沟通</button>
                <div id="limit" style="display:none"><h3>您已达到沟通上限</h3></div>
                """);
        try (var navigator=new BossChatNavigator(page)) {
            var chat=navigator.open(page.locator("#start"),false,false,null,3_000);
            assertNotNull(chat); assertNotSame(page,chat.page());
            page.evaluate("() => document.querySelector('#limit').style.display='block'");
            assertThrows(BossDailyLimitReachedException.class,navigator::checkDailyLimit);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void entersReadyChatInSameTabOrPopup(boolean popup, boolean modal) {
        fixture(popup, modal, true, true);
        try (var navigator = new BossChatNavigator(page)) {
            var chat = navigator.open(page.locator("#job-button"), true, true, null, 5_000);
            assertNotNull(chat,()->"pages="+context.pages().stream().map(Page::url).toList()+", fixture="+page.evaluate("() => ({attempts:window.fixtureOpenAttempts,opened:!!window.fixturePopup,closed:window.fixturePopup?.closed,href:window.fixturePopup?.location.href,confirmation:!!document.querySelector('#confirm-dialog')})"));
            assertTrue(chat.repeat());
            assertEquals("http://getjobs.test/chat", chat.page().url());
            assertEquals("new-input", chat.input().getAttribute("id"));
            assertEquals(popup, chat.page() != page);
        }
        assertTrue(context.pages().isEmpty());
    }

    @Test void confirmationDoesNotChangeFirstContactIntoRepeatChat() {
        fixture(false, true, true, true);
        try (var navigator = new BossChatNavigator(page)) {
            var chat = navigator.open(page.locator("#job-button"), false, true, null, 5_000);
            assertNotNull(chat);
            assertFalse(chat.repeat());
        }
    }

    @Test void disabledSwitchDoesNotConfirmModal() {
        fixture(false, true, true, true);
        try (var navigator = new BossChatNavigator(page)) {
            assertNull(navigator.open(page.locator("#job-button"), true, false, null, 3_000));
            assertEquals(0, page.locator("#confirm-dialog").count());
            assertEquals("http://getjobs.test/job", page.url());
        }
    }

    @Test void existingChatMustLeaveJobPageEvenIfAnInputIsVisible() {
        page.setContent("<button id='start'>继续沟通</button><textarea class='input-area'></textarea>");
        try (var navigator = new BossChatNavigator(page)) {
            assertNull(navigator.open(page.locator("#start"), true, true, null, 400));
        }
    }

    @Test void stopsLookingForModalAfterOneSecond() {
        page.setContent("""
                <button id="start" onclick="setTimeout(()=>{document.querySelector('#mount').innerHTML=
                '<div class=greet-boss-dialog><div class=greet-boss-footer><a class=sure-btn onclick=window.confirmClicked=true>继续沟通</a></div></div>'},1400)">立即沟通</button>
                <div id="mount"></div>
                """);
        try (var navigator = new BossChatNavigator(page)) {
            assertNull(navigator.open(page.locator("#start"), false, true, null, 2_100));
            assertEquals(1, page.locator(".greet-boss-dialog").count());
            assertNotEquals(true, page.evaluate("() => window.confirmClicked === true"));
        }
    }

    @Test void slowChatInputCanLoadAfterModalDetectionWindowEnds() {
        context.route("http://getjobs.test/slow-chat", route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("text/html")
                .setBody("<div id='mount'></div><script>setTimeout(()=>{document.querySelector('#mount').innerHTML=\"<textarea class='input-area'></textarea>\"},1400)</script>")));
        page.setContent("<button id='start' onclick=\"location.href='http://getjobs.test/slow-chat'\">继续沟通</button>");
        try (var navigator = new BossChatNavigator(page)) {
            assertNotNull(navigator.open(page.locator("#start"), true, true, null, 3_000));
        }
    }

    @Test void doesNotUseOldInputWhenNavigationFails() {
        fixture(false, true, false, true);
        try (var navigator = new BossChatNavigator(page)) {
            assertNull(navigator.open(page.locator("#job-button"), true, true, null, 800));
        }
    }

    @Test void noInputOrStopDoesNotReturnReadyChat() {
        fixture(true, true, true, false);
        try (var navigator = new BossChatNavigator(page)) {
            assertNull(navigator.open(page.locator("#job-button"), true, true, null, 1_000));
        }
        assertTrue(context.pages().isEmpty());
        page = context.newPage();
        fixture(false, true, true, true);
        try (var navigator = new BossChatNavigator(page)) {
            assertNull(navigator.open(page.locator("#job-button"), true, true, () -> true, 1_000));
        }
    }
}
