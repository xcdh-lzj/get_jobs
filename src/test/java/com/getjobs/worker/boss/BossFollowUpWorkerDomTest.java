package com.getjobs.worker.boss;

import com.getjobs.application.service.BossFollowUpStore;
import com.getjobs.application.service.BossService;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 通过拦截所有浏览器请求，在模拟页面点击文本消息按钮。 */
@EnabledIfEnvironmentVariable(named = "BOSS_CHAT_DOM_TEST", matches = "true")
class BossFollowUpWorkerDomTest {
    private static final String HOST = "https://www.zhipin.com";
    private static final String TEMPLATE = "您好，想再跟进一下这个岗位，感谢您的时间。";
    private static final String ALTERNATE = "您好，请问这个岗位还在招聘吗？";

    @Test void repeatsOnlyAfterTwelveHoursWithoutReplyAndStopsBeforeTheNextSend() {
        var jobs = List.of("unread", "read", "reply", "two", "recent", "unknown-time", "recorded", "first-fallback",
                "partial", "moved", "late-reply", "uncertain", "new", "different-date", "cancel", "after-stop").stream()
                .map(id -> new BossFollowUpStore.Job(id, "示例开发", HOST + "/job_detail/" + id + ".html", "", "示例公司", "HR",
                        firstMessageAt(id))).toList();
        var store = mock(BossFollowUpStore.class);
        var stopped = new AtomicBoolean();
        when(store.candidates(any(), anyInt(), anyLong())).thenReturn(jobs);
        when(store.claim(anyString(), anyLong())).thenAnswer(call -> {
            if ("participant:cancel".equals(call.getArgument(0))) stopped.set(true);
            return true;
        });
        when(store.confirmedTime("participant:recorded", "id:second")).thenReturn(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(13));
        var sends = new ArrayList<String>();
        var contents = new ArrayList<String>();
        var visited = new ArrayList<String>();
        var external = new AtomicInteger();
        var sendsToNew = new AtomicInteger();

        var env = new java.util.HashMap<>(System.getenv());
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        try (var playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
             var browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)
                     .setExecutablePath(Path.of(System.getenv("BOSS_TEST_CHROME"))));
             var context = browser.newContext()) {
            context.route("**/*", route -> {
                var url = URI.create(route.request().url());
                if (!"www.zhipin.com".equals(url.getHost())) {
                    external.incrementAndGet();
                    route.abort();
                    return;
                }
                String path = url.getPath();
                if (path.startsWith("/job_detail/")) {
                    String id = path.substring("/job_detail/".length(), path.length() - ".html".length());
                    visited.add(id);
                    html(route, "<a class='btn-startchat' href='/web/geek/chat?id=" + id + "'>"
                            + (id.equals("new") ? "立即沟通" : "继续沟通") + "</a>");
                } else if (path.equals("/web/geek/chat") && url.getQuery() != null && url.getQuery().startsWith("id=")) {
                    String id = url.getQuery().substring(3);
                    if (id.equals("new")) sendsToNew.incrementAndGet();
                    html(route, conversation(id));
                } else if (path.equals("/sent")) {
                    sends.add(url.getQuery().substring(3));
                    contents.add(route.request().postData());
                    route.fulfill(new Route.FulfillOptions().setStatus(200).setBody("ok"));
                } else {
                    html(route, "<p>模拟搜索页</p>");
                }
            });
            var listing = context.newPage();
            listing.navigate(HOST + "/web/geek/jobs");
            var config = new BossConfig();
            config.setFollowUpPolicy(new BossFollowUpPolicy(false, 48, 24, 20,
                    new BossFollowUpPolicy.DateSelection(List.of(), List.of("2024-02-28", "2024-02-29"))));
            config.setRepeatChatMessages("  " + TEMPLATE + "\n\n " + ALTERNATE + "\n\t");
            var result = new BossFollowUpWorker(store, mock(BossService.class))
                    .execute(listing, config, stopped::get, (message, current, total) -> {});
            assertEquals("stopped", result.state());
            assertEquals(14, result.checked());
            assertEquals(5, result.confirmed());
            assertEquals(List.of("unread", "read", "two", "recorded", "first-fallback", "uncertain"), sends);
            assertTrue(contents.stream().allMatch(Set.of(TEMPLATE, ALTERNATE)::contains));
            assertEquals(1, result.unconfirmed());
            assertFalse(visited.contains("after-stop"));
            assertEquals(0, sendsToNew.get());
            assertEquals(0, external.get());
            verify(store).cancelClaim(eq("participant:cancel"), anyLong());
            verify(store).rememberLastSent(argThat(job -> "recent".equals(job.id())), eq("participant:recent"), anyLong());
            verify(store, times(5)).confirm(anyString(), anyLong(), anyString(), anyLong());
        }
    }

    private static void html(Route route, String body) {
        route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(body));
    }

    private static String conversation(String id) {
        String extra = switch (id) {
            case "reply" -> "<li class='message-item item-friend'><span class='message-content'>你好</span></li>";
            case "two" -> earlierFollowUp("<span class='message-time'>2024-03-01 17:02</span>");
            case "recent" -> earlierFollowUp("<time datetime='" + java.time.Instant.now().minusSeconds(39_600) + "'></time>");
            case "unknown-time", "recorded" -> earlierFollowUp("");
            case "partial" -> "<li class='history-load-more'>点击加载更多</li>";
            default -> "";
        };
        String job = id.equals("moved") ? "different" : id;
        long originalTime = id.equals("first-fallback") ? 0 : firstMessageAt(id);
        return """
                <div class='position-content'><a class='position-name' href='/job_detail/%s.html'>示例开发</a></div>
                <div class='chat-message' data-conversation-id='%s'>
                  <ul class='im-list'>
                    <li class='message-item item-system'>已开始沟通</li>
                    <li class='message-item item-myself' data-message-id='first' data-timestamp='%d'>
                      <span class='message-content'>初次招呼语</span>
                      <span class='message-status'>%s</span>
                    </li>
                    %s
                  </ul>
                </div>
                <textarea class='input-area'></textarea>
                <button class='btn-send' onclick='send()'>发送</button>
                <script>
                  function send() {
                    const row = document.createElement('li');
                    row.className = 'message-item item-myself';
                    row.dataset.messageId = 'sent-by-program';
                    row.dataset.timestamp = Date.now();
                    const text = document.createElement('span');
                    text.className = 'message-content';
                    text.textContent = document.querySelector('textarea').value;
                    const status = document.createElement('span');
                    status.className = 'message-status';
                    status.textContent = '未读';
                    row.append(text, status);
                    fetch('/sent?id=%s', { method: 'POST', body: text.textContent }).then(() => { if (%s) document.querySelector('.im-list').append(row); });
                  }
                  if (%s) document.querySelector('textarea').addEventListener('input', () => {
                    const reply = document.createElement('li');
                    reply.className = 'message-item item-friend';
                    reply.textContent = '您好，刚看到您的消息';
                    document.querySelector('.im-list').append(reply);
                  });
                </script>
                """.formatted(job, id, originalTime, id.equals("unread") ? "未读" : "已读", extra, id, !id.equals("uncertain"), id.equals("late-reply"));
    }

    private static long firstMessageAt(String id) {
        int day = id.equals("different-date") ? 27 : id.equals("unread") ? 28 : 29;
        return LocalDateTime.of(2024, 2, day, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String earlierFollowUp(String time) {
        return "<li class='message-item item-myself' data-message-id='second'><span class='message-content'>第二条</span>" + time + "</li>";
    }
}
