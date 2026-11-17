package com.getjobs.worker.boss;

import com.microsoft.playwright.Page;
import org.json.JSONObject;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** 读取当前岗位招聘者的活跃标记，兼容独立详情页及搜索页详情面板。 */
public final class BossHrActivityReader {
    private static final Pattern SUFFIX = Pattern.compile(
            "(?:当前在线|正在活跃|刚刚活跃|刚刚在线|在线|今日活跃|今天活跃|今日在线|今天在线|昨日活跃|昨天活跃|本周活跃|本周在线|本月活跃|本月在线|半年前活跃|半年内活跃|长期未活跃|很久未活跃|很久没活跃|(?:最近|近)?(?:[0-9]{1,6}|一|两|二|三|四|五|六|七|八|九|十)(?:分钟|小时|天|日|周|星期|个月|月|年)(?:内|前)(?:活跃|在线))$");
    // 只检查本岗位招聘者信息区，不能用 JD 中的“在线”或推荐岗位的活跃时间。
    public static final String DOM_VALUES = """
            () => {
              const visible = el => el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden';
              const related = '.job-recommend,.job-recommend-main,.recommend-job,.similar-job,.recommend,.recommend-list,aside,ul.rec-job-list,ul.job-list-box';
              const roots = Array.from(document.querySelectorAll('.job-boss,.job-boss-info')).filter(el => visible(el) && !el.closest(related));
              const values = [];
              for (const root of roots) {
                const nodes = root.querySelectorAll('[class*="active-time"],[class*="active-status"],[class*="online"],h2,.name,.boss-name');
                for (const node of nodes) {
                  if (!visible(node)) continue;
                  for (const value of [node.innerText, node.getAttribute('aria-label'), node.getAttribute('title')])
                    if (value?.trim()) values.push(value.trim());
                }
              }
              return [...new Set(values)];
            }
            """;
    private BossHrActivityReader() {}

    public static String fromJson(JSONObject recruiter) {
        if (recruiter == null) return "";
        // false 只表示当前不在线，不能推断几天未活跃；true 则是明确的当前在线证据。
        if (Boolean.TRUE.equals(recruiter.opt("bossOnline"))) return "在线";
        for (String key : List.of("activeTimeDesc", "bossActiveTimeDesc")) {
            String text = recruiter.optString(key, "").strip();
            if (!text.isBlank() && !"null".equals(text)) return text;
        }
        return "";
    }

    public static String extract(String raw) {
        String text = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}\\u200B\\uFEFF]+", "");
        var match = SUFFIX.matcher(text);
        return match.find() ? match.group() : "";
    }

    public static String read(Page page) {
        Object value = page.evaluate(DOM_VALUES);
        if (!(value instanceof List<?> values)) return "";
        var statuses = values.stream().filter(String.class::isInstance).map(String.class::cast)
                .map(BossHrActivityReader::extract).filter(s -> !s.isBlank()).distinct().toList();
        // 若有明确在线标记，优先于同一区域较早的活跃范围。
        if (statuses.contains("在线") || statuses.contains("当前在线")) return "在线";
        return statuses.isEmpty() ? "" : statuses.getFirst();
    }

    public static String readWithFallback(Page page, String apiValue, boolean waitForActivity) {
        if ("在线".equals(apiValue) || "当前在线".equals(apiValue)) return "在线";
        String dom = read(page);
        if (!dom.isBlank()) return dom;
        if (!extract(apiValue).isBlank() || !waitForActivity) return apiValue;
        try {
            page.waitForFunction("pattern => (" + DOM_VALUES + ")().some(value => new RegExp(pattern).test(value.normalize('NFKC').replace(/[\\s\\u200B\\uFEFF]+/g,'')))", SUFFIX.pattern(),
                    new Page.WaitForFunctionOptions().setTimeout(2_000));
        } catch (com.microsoft.playwright.TimeoutError ignored) { /* 仍未知时由现有规则自动跳过，绝不伪造在线。 */ }
        dom = read(page);
        return dom.isBlank() ? apiValue : dom;
    }
}
