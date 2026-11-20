package com.getjobs.worker.boss;

import com.getjobs.application.service.BossFollowUpStore;
import com.getjobs.application.service.BossFollowUpStore.Job;
import com.getjobs.application.service.BossService;
import com.getjobs.worker.boss.review.BossCandidateCollector;
import com.getjobs.worker.boss.review.BossReviewJob;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** 从已投递岗位进入原会话，根据招聘者回复和最后一条本人消息决定是否跟进。 */
@Component
@RequiredArgsConstructor
public class BossFollowUpWorker {
    private static final String CHAT_BUTTON = "a.btn-startchat:visible, a.op-btn-chat:visible";
    private static final String SEND_BUTTON = "div.send-message:visible, button.btn-send:visible";
    private static final Pattern CONTINUE = Pattern.compile("^\\s*继续沟通\\s*$");
    private static final Pattern VERIFICATION = Pattern.compile("^\\s*(?:安全验证|访问过于频繁|请完成安全验证|登录已失效|当前 IP 地址可能存在异常访问行为[。！]?)\\s*$");
    private static final long INTERACTION_TIMEOUT = 2_000;
    private final BossFollowUpStore store;
    private final BossService bosses;

    public record Result(String state, int checked, int confirmed, int unconfirmed, int failed, String message) {}
    public record Progress(int checked, int total, int confirmed, int unconfirmed, int failed,
                           String message, String jobName, String level) {}
    private enum Outcome { SKIPPED, ANSWERED, CONFIRMED, UNCONFIRMED, FAILED }
    private record Check(Outcome outcome, String reason) {}
    private static final class Attempt {
        boolean claimed;
        long clickedAt;
    }

    public Result execute(Page listing, BossConfig config, BossFollowUpPolicy.Window window, Supplier<Boolean> stop,
                          Consumer<Progress> progress) {
        var candidates = store.candidates(window, System.currentTimeMillis());
        int total = candidates.size();
        if (total == 0) return new Result("completed", 0, 0, 0, 0, "所选投递日期暂无符合条件的待核对岗位");
        var companies = bosses.getBlackCompanies();
        var titles = bosses.getBlackJobs();
        var recruiters = bosses.getBlackRecruiters();
        int checked = 0, confirmed = 0, unconfirmed = 0, failed = 0;
        String state = "completed";
        String notice = "";
        progress.accept(new Progress(0, total, 0, 0, 0, "开始核对 " + total + " 个已投递岗位", null, "info"));
        for (Job job : candidates) {
            if (stop.get()) break;
            if (listing.isClosed()) {
                state = "failed";
                notice = "Boss 浏览器已关闭";
                break;
            }
            progress.accept(new Progress(checked, total, confirmed, unconfirmed, failed, "正在核对会话", job.title(), null));
            var attempt = new Attempt();
            Check result;
            try {
                boolean blockedJob = matches(companies, job.company()) || matches(titles, job.title())
                        || matches(recruiters, job.recruiterTitle());
                result = blockedJob ? new Check(Outcome.SKIPPED, "当前账号黑名单命中")
                        : check(listing, job, config, window, stop, attempt);
            } catch (BossDailyLimitReachedException limit) {
                state = "quota_reached";
                notice = "BOSS 提示今日沟通已达上限";
                result = problem(attempt, notice);
            } catch (IllegalStateException blocked) {
                state = "failed";
                notice = "会话页面需要登录或安全核对，本轮已结束";
                result = problem(attempt, notice);
            } catch (BossJobBlockedException | BossChatInteractionException | PlaywrightException unavailable) {
                result = problem(attempt, "会话不可用或页面出现提示，已跳过");
            }

            if (result.outcome() == Outcome.CONFIRMED) confirmed++;
            if (result.outcome() == Outcome.UNCONFIRMED) unconfirmed++;
            if (result.outcome() == Outcome.FAILED) failed++;
            if (stop.get() && result.outcome() == Outcome.SKIPPED) break;
            store.checked(job, result.outcome() == Outcome.ANSWERED);
            String level = switch (result.outcome()) {
                case CONFIRMED -> "success";
                case FAILED -> "error";
                case UNCONFIRMED -> "warning";
                default -> "info";
            };
            progress.accept(new Progress(++checked, total, confirmed, unconfirmed, failed, result.reason(), job.title(), level));
            if (!state.equals("completed")) break;
        }
        if (stop.get()) {
            state = "stopped";
            notice = "定期跟进已停止";
        }
        if ("completed".equals(state) && failed + unconfirmed > 0) {
            state = checked == failed ? "failed" : "partial";
        }
        String summary = "共核对 " + checked + " / " + total + " 个岗位，确认跟进 " + confirmed + " 条";
        if (unconfirmed > 0) summary += "，" + unconfirmed + " 条发送未确认，已记录本次尝试";
        if (failed > 0) summary += "，" + failed + " 个岗位核对失败";
        return new Result(state, checked, confirmed, unconfirmed, failed, notice.isBlank() ? summary : notice + "。 " + summary);
    }

    private Check check(Page listing, Job job, BossConfig config, BossFollowUpPolicy.Window window, Supplier<Boolean> stop, Attempt attempt) {
        String url = BossReviewJob.safeDetailUrl(job.url());
        if (url.isBlank() || !job.id().equals(BossCandidateCollector.idFromUrl(url))) {
            return new Check(Outcome.SKIPPED, "岗位链接无法核对");
        }
        if (stop.get()) return new Check(Outcome.SKIPPED, "任务已停止");
        Page detail = PlaywrightUtil.newBackgroundPage(listing);
        try (var navigator = new BossChatNavigator(detail)) {
            detail.navigate(url, new Page.NavigateOptions().setTimeout(12_000).setWaitUntil(WaitUntilState.COMMIT));
            if (stop.get()) return new Check(Outcome.SKIPPED, "任务已停止");
            requireAccessiblePage(detail);
            navigator.checkBlockingNotices();
            if (!URI.create(url).getPath().equals(URI.create(detail.url()).getPath())) {
                return new Check(Outcome.SKIPPED, "岗位详情已跳转");
            }
            Locator button = detail.locator(CHAT_BUTTON).filter(new Locator.FilterOptions().setHasText(CONTINUE));
            long entryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!stop.get() && !button.first().isVisible() && System.nanoTime() < entryDeadline) {
                detail.waitForTimeout(100);
            }
            if (stop.get()) return new Check(Outcome.SKIPPED, "任务已停止");
            if (button.count() == 0) {
                requireAccessiblePage(detail);
                return new Check(Outcome.SKIPPED, "未找到可继续的原会话");
            }
            if (button.count() != 1 || stop.get()) return new Check(Outcome.SKIPPED, "原会话入口无法唯一核对");
            var chat = navigator.openExisting(button, stop, 12_000, BossFollowUpWorker::requireAccessiblePage);
            if (stop.get()) return new Check(Outcome.SKIPPED, "任务已停止");
            if (chat == null) {
                navigator.checkBlockingNotices();
                return new Check(Outcome.FAILED, "原会话未就绪");
            }
            Page page = chat.page();
            navigator.checkBlockingNotices();
            var first = BossFollowUpConversation.read(page);
            // 对话刚打开时消息可能仍在加载，等待页面稳定后重新核对。
            page.waitForTimeout(650);
            if (stop.get()) return new Check(Outcome.SKIPPED, "任务已停止");
            var current = BossFollowUpConversation.read(page);
            if (!current.matched(job) || !first.matched(job)) {
                return new Check(Outcome.SKIPPED, "无法确认会话属于原岗位");
            }
            if (current.answered()) return new Check(Outcome.ANSWERED, "招聘者已有回复");
            var anchor = current.unanswered(window);
            if (anchor == null || !anchor.sameAs(first.unanswered(window))) {
                return new Check(Outcome.SKIPPED, "无法确认完整的未回复会话");
            }
            String key = current.conversationKey(job, anchor.first());
            if (key.isBlank() || !key.equals(first.conversationKey(job, anchor.first()))) {
                return new Check(Outcome.SKIPPED, "会话身份无法核对");
            }
            long lastSentAt = lastSentAt(job, key, anchor);
            if (lastSentAt <= 0) return new Check(Outcome.SKIPPED, "未能确认最后一条本人消息的发送时间");
            store.rememberLastSent(job, key, lastSentAt);
            if (System.currentTimeMillis() - lastSentAt <= BossFollowUpPolicy.MESSAGE_INTERVAL_MILLIS) {
                return new Check(Outcome.SKIPPED, "距离最后一条本人消息未超过 12 小时");
            }
            Object draft = chat.input().evaluate("el => (el.value ?? el.textContent ?? '').trim()", null,
                    new Locator.EvaluateOptions().setTimeout(INTERACTION_TIMEOUT));
            if (!(draft instanceof String value) || !value.isEmpty()) {
                return new Check(Outcome.SKIPPED, "会话里有尚未发送的草稿");
            }
            String message = config.randomRepeatChatMessage();
            if (message == null) return new Check(Outcome.SKIPPED, "未配置跟进文案");
            if (!navigator.fillMessage(chat.input(), message, stop, INTERACTION_TIMEOUT)) {
                return stop.get() ? new Check(Outcome.SKIPPED, "任务已停止") : new Check(Outcome.FAILED, "跟进文案未填入");
            }
            Locator send = page.locator(SEND_BUTTON);
            if (send.count() != 1) return new Check(Outcome.SKIPPED, "无法定位唯一的发送按钮");
            boolean clicked = navigator.sendMessage(send, stop, INTERACTION_TIMEOUT, () -> {
                navigator.checkBlockingNotices();
                var latest = BossFollowUpConversation.read(page);
                var latestAnchor = latest.unanswered(window);
                if (stop.get() || !latest.matched(job) || !anchor.sameAs(latestAnchor)
                        || !key.equals(latest.conversationKey(job, anchor.first()))) return false;
                long latestSentAt = lastSentAt(job, key, latestAnchor);
                attempt.clickedAt = System.currentTimeMillis();
                if (latestSentAt <= 0 || attempt.clickedAt - latestSentAt <= BossFollowUpPolicy.MESSAGE_INTERVAL_MILLIS) return false;
                attempt.claimed = store.claim(key, attempt.clickedAt);
                return attempt.claimed;
            });
            if (!clicked) {
                if (attempt.claimed) {
                    store.cancelClaim(key, attempt.clickedAt);
                    attempt.claimed = false;
                }
                return new Check(Outcome.SKIPPED, "发送前条件已变化，或最近 12 小时内已尝试跟进");
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            long acknowledgedSince = 0;
            while (!stop.get() && System.nanoTime() < deadline) {
                navigator.checkBlockingNotices();
                var receipt = BossFollowUpConversation.read(page).confirmation(job, key, anchor, message, attempt.clickedAt);
                if (receipt != null) {
                    if (acknowledgedSince == 0) acknowledgedSince = System.nanoTime();
                    if (System.nanoTime() - acknowledgedSince >= TimeUnit.MILLISECONDS.toNanos(500)) {
                        long sentAt = receipt.latest().timestamp() > 0 ? receipt.latest().timestamp() : System.currentTimeMillis();
                        store.confirm(key, attempt.clickedAt, receipt.receiptKey(), sentAt);
                        return new Check(Outcome.CONFIRMED, "已确认发送一条跟进消息");
                    }
                } else {
                    acknowledgedSince = 0;
                }
                if (stop.get()) break;
                page.waitForTimeout(250);
            }
            return problem(attempt, "未取得跟进消息回执");
        }
    }

    private long lastSentAt(Job job, String key, BossFollowUpConversation.Anchor anchor) {
        if (anchor.latest().timestamp() > 0) return anchor.latest().timestamp();
        long confirmedAt = store.confirmedTime(key, anchor.receiptKey());
        if (confirmedAt > 0) return confirmedAt;
        return anchor.sentCount() == 1 ? job.contactedAt() : 0;
    }

    private static Check problem(Attempt attempt, String reason) {
        return attempt.claimed ? new Check(Outcome.UNCONFIRMED, reason + "，已记录本次尝试")
                : new Check(Outcome.FAILED, reason);
    }

    private static boolean matches(Set<String> terms, String value) {
        return value != null && terms.stream().anyMatch(value::contains);
    }

    private static void requireAccessiblePage(Page page) {
        URI url = URI.create(page.url());
        String path = Objects.toString(url.getPath(), "");
        if (!"https".equals(url.getScheme()) || !"www.zhipin.com".equals(url.getHost())
                || path.matches("(?i).*(?:verify|captcha|security-check|/login|/error/403).*")
                || page.getByText(VERIFICATION).first().isVisible()) {
            throw new IllegalStateException("BOSS 要求登录或验证");
        }
    }
}
