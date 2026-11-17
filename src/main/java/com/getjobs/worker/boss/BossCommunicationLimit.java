package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import java.util.regex.Pattern;

/** 只识别可见的额度提示，不用本次任务的发送数量推算平台当天剩余额度。 */
final class BossCommunicationLimit {
    private static final Pattern NOTICE = Pattern.compile(
            "^\\s*(?:"
                    + "(?:您|你)?\\s*已(?:经)?\\s*达到\\s*(?:(?:今日|今天|当日|每日)\\s*)?沟通\\s*(?:人数|次数)?\\s*上限"
                    + "|(?:今日|今天|当日)\\s*沟通\\s*(?:人数|次数)?\\s*已?\\s*(?:达到|达)\\s*上限"
                    + "|(?:您|你)?\\s*(?:今天|今日|当日)\\s*已与\\s*\\d+\\s*位\\s*BOSS\\s*沟通.*?(?:明天|明日|次日).*"
                    + ")\\s*[！!。～~]*\\s*$",
            // 传给 Playwright 的正则只使用 JavaScript 支持的 i/s 标志。
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private BossCommunicationLimit() {}

    static void check(Page page) {
        if (page == null || page.isClosed()) return;
        try {
            Locator notices = page.getByText(NOTICE);
            for (int i = 0; i < notices.count(); i++) {
                Locator notice = notices.nth(i);
                if (notice.isVisible()) {
                    String text = notice.textContent();
                    throw new BossDailyLimitReachedException(text == null ? "您已达到沟通上限" : text.strip());
                }
            }
            var remaining=BossQuotaReminder.find(page);
            if(remaining!=null && remaining.counts().remaining()==0)throw new BossDailyLimitReachedException(remaining.text());
        } catch (PlaywrightException navigating) {
            // 导航中的暂时不可读不代表达到上限；调用方会在等待聊天页期间继续检查。
            if (page.isClosed()) return;
            String message = navigating.getMessage();
            if (message == null || !(message.contains("Execution context was destroyed")
                    || message.contains("Cannot find context") || message.contains("Frame was detached")
                    || message.contains("Object doesn't exist"))) throw navigating;
        }
    }
}
