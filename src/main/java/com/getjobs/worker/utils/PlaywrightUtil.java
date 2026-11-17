package com.getjobs.worker.utils;

import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 线程休眠工具。
 * <p>
 * 这里原来还有一整套独立的浏览器初始化（launch + newContext + 伪造 UA + addInitScript 注入 stealth 脚本），
 * 那是迁到 patchright 之前的旧方案，全部是反检测的泄漏点，已删除。
 * 浏览器统一由 {@link com.getjobs.worker.manager.PlaywrightManager} 管理，不要在这里再起一个。
 */
@Slf4j
public class PlaywrightUtil {

    /**
     * 在同一 Chromium 上下文中创建后台标签页。
     * 普通 newPage() 默认按前台标签创建，在 macOS 上可能反复激活 Chrome。
     * 用唯一的空白页地址关联 Playwright Page，避免误用其他平台同时打开的标签页。
     */
    public static Page newBackgroundPage(Page source) {
        BrowserContext context = source.context();
        CDPSession session = context.newCDPSession(source);
        String marker = "about:blank#getjobs-" + UUID.randomUUID();
        String targetId = null;
        try {
            JsonObject params = new JsonObject();
            params.addProperty("url", marker);
            params.addProperty("background", true);
            JsonObject sourceInfo = session.send("Target.getTargetInfo").getAsJsonObject("targetInfo");
            if (sourceInfo.has("browserContextId") && !sourceInfo.get("browserContextId").isJsonNull()) {
                params.add("browserContextId", sourceInfo.get("browserContextId"));
            }
            targetId = session.send("Target.createTarget", params).get("targetId").getAsString();
            Page[] result = new Page[1];
            context.waitForCondition(() -> {
                result[0] = context.pages().stream()
                        .filter(page -> !page.isClosed() && marker.equals(page.url()))
                        .findFirst().orElse(null);
                return result[0] != null;
            }, new BrowserContext.WaitForConditionOptions().setTimeout(30_000));
            return result[0];
        } catch (RuntimeException error) {
            if (targetId != null) {
                JsonObject params = new JsonObject();
                params.addProperty("targetId", targetId);
                try {
                    session.send("Target.closeTarget", params);
                } catch (RuntimeException cleanupError) {
                    error.addSuppressed(cleanupError);
                }
            }
            // 创建失败时直接报错，不退回会抢焦点的普通 newPage()。
            throw error;
        } finally {
            try {
                session.detach();
            } catch (RuntimeException error) {
                log.debug("后台标签页 CDP 会话释放失败：{}", error.getMessage());
            }
        }
    }

    /**
     * 等待指定时间（秒）
     *
     * @param seconds 等待的秒数
     */
    public static void sleep(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Sleep被中断", e);
        }
    }
}
