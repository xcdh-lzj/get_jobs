package com.getjobs.worker.boss;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 所有地址均为路由模拟；不连接 BOSS，不给招聘者发送消息。 */
@EnabledIfEnvironmentVariable(named="BOSS_CHAT_DOM_TEST",matches="true")
class BossQuotaReminderDomTest {
    static Playwright playwright;static Browser browser;
    BrowserContext context;Page page;
    @BeforeAll static void launch() {
        var env=new HashMap<>(System.getenv());env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1");
        playwright=Playwright.create(new Playwright.CreateOptions().setEnv(env));
        browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setArgs(List.of("--disable-popup-blocking")).setExecutablePath(Path.of(System.getenv("BOSS_TEST_CHROME"))));
    }
    @AfterAll static void shutdown(){browser.close();playwright.close();}
    @BeforeEach void setup(){context=browser.newContext();page=context.newPage();}
    @AfterEach void cleanup(){context.close();}
    private static String reminder(int remaining,String action) {
        return "<div class='dialog-wrap' role='dialog' id='quota'><div class='dialog-layer'></div><div class='dialog-container'><h3>温馨提示</h3><p>您今天已与120位BOSS沟通，还剩"+remaining+"次沟通机会哦</p><div class='dialog-footer'><a href='javascript:;' id='quota-ok' onclick=\""+action+"\">好</a></div></div></div>";
    }
    private static String quote(String text){return org.json.JSONObject.quote(text);}

    @ParameterizedTest @CsvSource({"false,0","true,0","false,1300","true,1300"})
    void acknowledgesBeforeEnteringSameTabOrPopupEvenAfterOneSecond(boolean popup,int delay) {
        var starts=new AtomicInteger();var acknowledgements=new AtomicInteger();var progress=new ArrayList<String>();
        String modal=reminder(30,"fetch('/ack').then(()=>{location.href='/chat'})");
        String show=popup?"window.open('/notice')":"document.querySelector('#mount').innerHTML="+quote(modal)+";document.querySelector('#old-input').style.display='block'";
        String job="<button id='start' onclick=\"fetch('/entry').then(()=>setTimeout(()=>{"+show.replace("\"","&quot;")+"},"+delay+"))\">立即沟通</button><div id='mount'></div><textarea class='input-area' id='old-input' style='display:none'></textarea>";
        context.route("http://getjobs.test/**",route -> {
            String path=java.net.URI.create(route.request().url()).getPath();String body;
            if(path.equals("/entry")){starts.incrementAndGet();body="ok";}
            else if(path.equals("/ack")){acknowledgements.incrementAndGet();body="ok";}
            else if(path.equals("/chat"))body="<textarea class='input-area' id='new-input'></textarea>";
            else if(path.equals("/notice"))body=modal+"<textarea class='input-area' id='old-input'></textarea>";
            else body=job;
            route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(body));
        });
        page.navigate("http://getjobs.test/job");Page unrelated=context.newPage();
        try(var navigator=new BossChatNavigator(page,progress::add)) {
            var chat=navigator.open(page.locator("#start"),false,false,null,4_000);
            assertNotNull(chat);assertFalse(chat.repeat());assertEquals("new-input",chat.input().getAttribute("id"));
            assertEquals(popup,chat.page()!=page);assertEquals(1,starts.get());assertEquals(1,acknowledgements.get());
            assertEquals(1,progress.size());assertTrue(progress.getFirst().contains("剩余 30 次"));
        }
        assertFalse(unrelated.isClosed());assertEquals(1,context.pages().size());
    }

    @ParameterizedTest @CsvSource({"a","button","div"})
    void anExistingReminderIsClearedBeforeClickingTheEntryExactlyOnce(String tag) {
        String modal=reminder(30,"window.acks=(window.acks||0)+1;document.querySelector('#quota').remove()")
                .replace("<a href='javascript:;'","<"+tag).replace("</a>","</"+tag+">");
        page.setContent(modal+"<button id='start' onclick=\"window.entries=(window.entries||0)+1;document.querySelector('#mount').innerHTML='<textarea class=input-area></textarea>'\">立即沟通</button><div id='mount'></div>");
        try(var navigator=new BossChatNavigator(page)) {
            assertNotNull(navigator.open(page.locator("#start"),false,false,null,2_000));
            assertEquals(1,page.evaluate("() => window.acks"));assertEquals(1,page.evaluate("() => window.entries"));
        }
    }
    @Test void reminderThatItselfOpensInlineChatDoesNotClickEntryAgain() {
        page.setContent(reminder(30,"document.querySelector('#quota').remove();document.querySelector('#mount').innerHTML='<textarea class=input-area></textarea>'")
                +"<button id='start' onclick='window.entryClicked=true'>立即沟通</button><div id='mount'></div>");
        try(var navigator=new BossChatNavigator(page)) {
            assertNotNull(navigator.open(page.locator("#start"),false,false,null,2_000));
            assertNotEquals(true,page.evaluate("() => window.entryClicked === true"));
        }
    }
    @Test void lateReminderIsAlsoHandledBeforeSendingWithoutSendingAnythingInTheNavigator() {
        page.setContent("<button id='start' onclick=\"document.querySelector('#mount').innerHTML='<textarea class=input-area></textarea>'\">立即沟通</button><div id='mount'></div><div id='notice'></div><button onclick='window.sent=true'>发送</button>");
        try(var navigator=new BossChatNavigator(page)) {
            assertNotNull(navigator.open(page.locator("#start"),false,false,null,2_000));
            page.locator("#notice").evaluate("(el,html)=>el.innerHTML=html",reminder(30,"window.acks=(window.acks||0)+1;document.querySelector('#quota').remove()"));
            assertTrue(navigator.prepareToSend(null,1_500));assertEquals(1,page.evaluate("() => window.acks"));
            assertNotEquals(true,page.evaluate("() => window.sent === true"));
        }
    }
    @Test void positiveReminderCanBeFollowedByTheExistingContinueConfirmation() {
        String greet="<div class='greet-boss-dialog'><div class='greet-boss-footer'><a class='sure-btn' onclick=\"window.greetAck=(window.greetAck||0)+1;location.href='http://getjobs.test/chat'\">继续沟通</a></div></div>";
        String modal=reminder(30,"document.querySelector('#quota').remove();document.querySelector('#greet').innerHTML=window.greetHtml");
        context.route("http://getjobs.test/chat",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("<textarea class='input-area' id='new-input'></textarea>")));
        page.setContent("<button id='start'>立即沟通</button><div id='mount'></div><div id='greet'></div><textarea class='input-area' style='display:none'></textarea>");
        page.evaluate("html => window.greetHtml=html",greet);
        page.evaluate("html => document.querySelector('#start').onclick=()=>setTimeout(()=>document.querySelector('#mount').innerHTML=html,1300)",modal);
        try(var navigator=new BossChatNavigator(page)) {
            var chat=navigator.open(page.locator("#start"),false,false,null,4_000);
            assertNotNull(chat);assertEquals("new-input",chat.input().getAttribute("id"));
        }
    }
    @Test void zeroRemainingArrivingAfterChatIsReadyStillPreventsSending() {
        page.setContent("<button id='start' onclick=\"document.querySelector('#mount').innerHTML='<textarea class=input-area></textarea>'\">立即沟通</button><div id='mount'></div><div id='notice'></div>");
        try(var navigator=new BossChatNavigator(page)) {
            assertNotNull(navigator.open(page.locator("#start"),false,false,null,2_000));
            page.locator("#notice").evaluate("(el,html)=>el.innerHTML=html",reminder(0,"window.ack=true"));
            assertThrows(BossDailyLimitReachedException.class,()->navigator.prepareToSend(null,1_000));
            assertNotEquals(true,page.evaluate("() => window.ack === true"));
        }
    }
    @ParameterizedTest @CsvSource({"0,false","30,true"})
    void zeroRemainingOrAnExplicitHardLimitNeverAcknowledgesTheInformationButton(int remaining,boolean hardLimit) {
        page.setContent(reminder(remaining,"window.ack=true")+(hardLimit?"<h3>您已达到沟通上限</h3>":"")+"<button id='start' onclick='window.entry=true'>立即沟通</button><textarea class='input-area'></textarea>");
        try(var navigator=new BossChatNavigator(page)) {
            assertThrows(BossDailyLimitReachedException.class,()->navigator.open(page.locator("#start"),false,false,null,2_000));
            assertNotEquals(true,page.evaluate("() => window.ack === true"));assertNotEquals(true,page.evaluate("() => window.entry === true"));
        }
    }
    @Test void stillVisibleReminderIsNotClickedRepeatedlyAndDoesNotExposeUnderlyingInput() {
        String modal=reminder(30,"window.acks=(window.acks||0)+1");
        page.setContent(modal+"<button id='start' onclick='window.entry=true'>立即沟通</button><textarea class='input-area'></textarea>");
        try(var navigator=new BossChatNavigator(page)) {
            assertNull(navigator.open(page.locator("#start"),false,false,null,700));
            assertEquals(1,page.evaluate("() => window.acks"));assertNotEquals(true,page.evaluate("() => window.entry === true"));
        }
    }
    @Test void hiddenNoticeAndQuotaCopyOutsideADialogAreNotClicked() {
        page.setContent("<section class='job-sec-text'><p>您今天已与120位BOSS沟通，还剩30次沟通机会哦</p></section><div style='display:none'>"+reminder(30,"window.ack=true")+"</div><button id='start' onclick=\"document.querySelector('#mount').innerHTML='<textarea class=input-area></textarea>'\">立即沟通</button><div id='mount'></div>");
        try(var navigator=new BossChatNavigator(page)) {
            assertNotNull(navigator.open(page.locator("#start"),false,false,null,2_000));
            assertNotEquals(true,page.evaluate("() => window.ack === true"));
        }
    }
    @Test void stopAndAdditionalPurchaseTextNeverConfirmAQuotaNotice() {
        page.setContent(reminder(30,"window.ack=true")+"<button id='start'>立即沟通</button>");
        try(var navigator=new BossChatNavigator(page)) {
            assertNull(navigator.open(page.locator("#start"),false,false,()->true,1_000));
            assertNotEquals(true,page.evaluate("() => window.ack === true"));
        }
        page=context.newPage();page.setContent(reminder(30,"window.ack=true").replace("<h3>温馨提示</h3>","<h3>温馨提示</h3><p>购买会员</p>")+"<button id='start'>立即沟通</button><textarea class='input-area'></textarea>");
        try(var navigator=new BossChatNavigator(page)) {
            assertNull(navigator.open(page.locator("#start"),false,false,null,500));
            assertNotEquals(true,page.evaluate("() => window.ack === true"));
        }
    }
}
