package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** 识别已有会话里要求切换沟通职位的确认框。 */
final class BossNewPositionDialog {
    private static final Pattern CONFIRM = Pattern.compile("^沟通新职位$");
    private static final String CONTAINER = "xpath=ancestor::*[not(self::body or self::html) and "
            + "(@data-type='boss-dialog' or @role='dialog' or @role='alertdialog' "
            + "or contains(@class,'dialog-wrap') or contains(@class,'boss-dialog')) "
            + "and .//*[normalize-space(.)='温馨提示']][1]";
    private static final Pattern NOTICE = Pattern.compile(
            "[×✕]?温馨提示[×✕]?[您你]与[该此]BOSS已(?:经)?沟通过,?是否就新职位[^?]{1,160}继续沟通\\?"
                    + "注:更换沟通职位将通知BOSS[。.]?取消沟通新职位[×✕]?",
            Pattern.CASE_INSENSITIVE);

    private BossNewPositionDialog() {}

    static boolean matches(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("[\\s\\p{Z}]+", "");
        return NOTICE.matcher(normalized).matches();
    }

    static Locator findConfirmation(Page page) {
        Locator buttons = page.getByText(CONFIRM);
        Locator found = null;
        for (int i = 0; i < buttons.count(); i++) {
            Locator button = buttons.nth(i);
            if (!button.isVisible()) continue;
            Locator dialog = button.locator(CONTAINER);
            if (dialog.count() != 1 || !dialog.isVisible()
                    || !matches(dialog.innerText(new Locator.InnerTextOptions().setTimeout(300)))) continue;
            Locator confirmation = dialog.getByText(CONFIRM);
            if (confirmation.count() != 1 || found != null) return null;
            found = confirmation;
        }
        return found;
    }
}
