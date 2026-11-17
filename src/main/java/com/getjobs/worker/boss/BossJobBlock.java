package com.getjobs.worker.boss;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** 只识别可见弹窗中的平台沟通阻断，不点击“个人中心”或尝试绕过岗位要求。 */
final class BossJobBlock {
    static final Pattern TITLE = Pattern.compile("^\\s*工作经历不匹配\\s*$");
    static final Pattern BODY = Pattern.compile(
            "^\\s*[您你]的\\s*工作经历\\s*与\\s*[该此]岗位要求\\s*不匹配\\s*[,，。]?\\s*"
                    + "得到回复的概率较低\\s*[,，。]?\\s*建议\\s*[您你]\\s*选择其他职位沟通\\s*[。!！]?\\s*$");
    static final String MODAL = "xpath=ancestor::*[not(self::body or self::html) and "
            + "(@role='dialog' or @role='alertdialog' or contains(@class,'dialog') or contains(@class,'modal') or contains(@class,'popup')) "
            + "and .//*[contains(normalize-space(.),'岗位要求')]][1]";

    private BossJobBlock() {}

    static boolean matches(String title, String body) {
        return TITLE.matcher(normalize(title)).matches() && BODY.matcher(normalize(body)).matches();
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).strip();
    }

    static void check(Page page) {
        if (page == null || page.isClosed()) return;
        try {
            var titles = page.getByText(TITLE);
            for (int i = 0; i < titles.count(); i++) {
                var title = titles.nth(i);
                if (!title.isVisible()) continue;
                var modal = title.locator(MODAL);
                if (modal.count() != 1 || !modal.isVisible()) continue;
                var messages = modal.getByText(BODY);
                for (int j = 0; j < messages.count(); j++) {
                    var message = messages.nth(j);
                    if (message.isVisible() && matches(title.textContent(), message.textContent())) {
                        throw new BossJobBlockedException("Boss 提示工作经历与该岗位要求不匹配");
                    }
                }
            }
        } catch (PlaywrightException navigating) {
            String message = navigating.getMessage();
            if (page.isClosed() || message != null && (message.contains("Execution context was destroyed")
                    || message.contains("Cannot find context") || message.contains("Frame was detached")
                    || message.contains("Object doesn't exist"))) return;
            throw navigating;
        }
    }
}
