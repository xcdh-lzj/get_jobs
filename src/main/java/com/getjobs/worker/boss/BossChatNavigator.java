package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.ElementHandle;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 负责沟通页面导航、弹窗处理和聊天控件操作。 */
@Slf4j
final class BossChatNavigator implements AutoCloseable {
    static final String INPUT = "div#chat-input.chat-input[contenteditable='true'], textarea.input-area";
    // 2026-10-21 从真实 Boss 页面 DOM 采集确认：两个沟通入口都可能出现此通知框。
    static final String GREET_CONFIRM = ".greet-boss-dialog:visible .greet-boss-footer a.sure-btn";
    // 先定位可见的“留在此页”，再取同时包含两个按钮的最近共同祖先。
    // 排除 body/html，不能退化为全页面点击同名的岗位按钮。
    static final String STAY = ":text-is('留在此页'):visible";
    static final String CONFIRM_CONTAINER =
            "xpath=ancestor::*[not(self::body or self::html) and .//*[normalize-space(.)='继续沟通']][1]";

    record Chat(Page page, Locator input, boolean repeat) {}

    private final Page origin;
    private final List<Page> popups = new ArrayList<>();
    private final Consumer<Page> popupListener = popups::add;
    private final Consumer<String> progress;
    private record ReminderAttempt(ElementHandle message,String text) {}
    private final Map<Page,ReminderAttempt> reminderAttempts=new IdentityHashMap<>();
    private int reminderClicks;
    private boolean allowRepeatChat;
    private Page chatPage;
    private Page newPositionPage;
    private boolean draftFilled;

    BossChatNavigator(Page origin) {
        this(origin,ignored -> {});
    }
    BossChatNavigator(Page origin,Consumer<String> progress) {
        this.origin = origin;
        this.progress=progress;
        origin.onPopup(popupListener);
    }

    Chat open(Locator button, boolean repeat, boolean allowRepeat, Supplier<Boolean> stop, long timeoutMs) {
        if (stop != null && Boolean.TRUE.equals(stop.get())) return null;
        if (repeat && !allowRepeat) return null;
        allowRepeatChat = allowRepeat;
        checkBlockingNotices();
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        String entryUrl = origin.url();
        boolean inputInitiallyVisible=origin.locator(INPUT).first().isVisible();
        boolean entryClicked=false,reminderSeen=false;
        // 已沟通确认框在点击后检测1秒；额度提醒确认后为后续确认框重开1秒窗口。
        long confirmationDeadline = Math.min(deadline, System.nanoTime() + 1_000_000_000L);
        Page confirmationPage = null;
        String confirmationUrl = null;
        boolean confirmed = false;
        while (System.nanoTime() < deadline) {
            if (stop != null && Boolean.TRUE.equals(stop.get())) return null;
            // 上限弹框可能出现在原详情页，也可能出现在新窗口；不受1秒确认框检测窗口限制。
            checkBlockingNotices();
            if(handleQuotaReminders(deadline,stop)) {
                reminderSeen=true;
                if(!confirmed)confirmationDeadline=Math.min(deadline,System.nanoTime()+1_000_000_000L);
                if(!pump())return null;
                continue;
            }
            if(!entryClicked) {
                // 提醒确认本身可能已经打开聊天页，不能再点击一次沟通按钮。
                boolean progressed=reminderSeen && (!origin.url().equals(entryUrl) || popups.stream().anyMatch(p -> !p.isClosed())
                        || !repeat && !inputInitiallyVisible && origin.locator(INPUT).first().isVisible());
                entryClicked=true;
                if(!progressed)try {
                    long remaining=Math.max(1,(deadline-System.nanoTime())/1_000_000L);
                    button.click(new Locator.ClickOptions().setTimeout(Math.min(remaining,5_000)));
                }catch(PlaywrightException navigationError){log.debug("点击岗位按钮时发生页面切换，继续检查聊天入口：{}",navigationError.getMessage());}
                confirmationDeadline=Math.min(deadline,System.nanoTime()+1_000_000_000L);
                // 点击可能立即出现额度提醒，先重新检查，不能接受弹窗下方的输入框。
                continue;
            }
            if (handleNewPosition(deadline, stop)) {
                if (!pump()) return null;
                continue;
            }
            List<Page> candidates = new ArrayList<>(popups);
            if (newPositionPage != null) candidates = List.of(newPositionPage);
            else if (candidates.stream().allMatch(Page::isClosed)) candidates.add(origin);
            for (Page candidate : candidates) {
                if (candidate.isClosed()) continue;
                try {
                    Locator confirmation = newPositionPage == null && !confirmed && System.nanoTime() < confirmationDeadline
                            ? findConfirmation(candidate) : null;
                    if (confirmation != null) {
                        // “已向BOSS发送消息”也是首次打招呼的结果通知，不能据此改成旧会话。
                        if (!confirmed) {
                            confirmationPage = candidate;
                            confirmationUrl = candidate.url();
                            // 每次只点击一次；弹框仍在或跳转未完成时继续等待，不能发送。
                            confirmed = true;
                            long remainingMs = (confirmationDeadline - System.nanoTime()) / 1_000_000L;
                            if (remainingMs <= 0) continue;
                            try {
                                confirmation.click(new Locator.ClickOptions().setTimeout(remainingMs));
                            } catch (PlaywrightException clickError) {
                                log.warn("弹框确认未在1秒检测窗口内完成，跳过当前岗位");
                                return null;
                            }
                            log.info("已点击已沟通确认框内的【继续沟通】，等待聊天页跳转");
                        }
                        continue;
                    }
                    if (newPositionPage == null && confirmed && candidate == confirmationPage && candidate.url().equals(confirmationUrl)) {
                        continue;
                    }
                    if (newPositionPage == null && repeat && candidate == origin && candidate.url().equals(entryUrl)) continue;
                    // 每轮新建定位器，避免导航后继续使用旧 frame 的输入框。
                    Locator input = candidate.locator(INPUT).first();
                    if (input.isVisible() && input.isEditable(new Locator.IsEditableOptions().setTimeout(500))) {
                        checkBlockingNotices();
                        chatPage = candidate;
                        return new Chat(candidate, input, repeat || switchedPosition());
                    }
                } catch (PlaywrightException navigationError) {
                    log.debug("聊天页正在切换，继续等待：{}", navigationError.getMessage());
                }
            }
            if(!pump())return null;
        }
        log.warn("等待聊天页超时，未发送消息；请检查是否存在未识别的弹框");
        return null;
    }

    boolean switchedPosition() {
        return newPositionPage != null;
    }

    void checkDailyLimit() {
        BossCommunicationLimit.check(origin);
        // 查询会派发浏览器事件，新弹窗可能在查询期间加入列表。
        for (Page popup : new ArrayList<>(popups)) BossCommunicationLimit.check(popup);
    }

    void checkBlockingNotices() {
        // 账号额度用尽优先处理；岗位不匹配只结束本岗位。
        checkDailyLimit();
        BossJobBlock.check(origin);
        for (Page popup : new ArrayList<>(popups)) BossJobBlock.check(popup);
    }

    /** 输入和发送前再次处理晚到的沟通弹窗。 */
    boolean prepareToSend(Supplier<Boolean> stop,long timeoutMs) {
        long deadline=System.nanoTime()+timeoutMs*1_000_000L;
        while(System.nanoTime()<deadline) {
            if(stop!=null && Boolean.TRUE.equals(stop.get()))return false;
            checkBlockingNotices();
            if(!handleQuotaReminders(deadline,stop) && !handleNewPosition(deadline,stop)) {
                checkBlockingNotices();
                return true;
            }
            if(!pump())return false;
        }
        log.warn("沟通弹窗尚未关闭，当前岗位未发送消息");return false;
    }

    /** 阻断出现于 open 返回之后时，再核对一次；单个控件操作不使用默认30秒等待。 */
    boolean fillMessage(Locator input,String message,Supplier<Boolean> stop,long timeoutMs) {
        try {
            if(!prepareInteraction(input,stop,timeoutMs))return false;
            input.click(new Locator.ClickOptions().setTimeout(timeoutMs));
            if(!prepareInteraction(input,stop,timeoutMs))return false;
            // fill 同时支持 textarea 与 contenteditable，并派发正常的 input 事件。
            input.fill(message,new Locator.FillOptions().setTimeout(timeoutMs));
            draftFilled = true;
            checkBlockingNotices();return true;
        }catch(PlaywrightException blocked) {throw interactionFailure(input,"聊天输入框暂不可操作",blocked);}
    }

    boolean sendMessage(Locator send,Supplier<Boolean> stop,long timeoutMs) {
        try {
            if(!prepareInteraction(send,stop,timeoutMs))return false;
            send.click(new Locator.ClickOptions().setTimeout(timeoutMs));
            checkBlockingNotices();return true;
        }catch(PlaywrightException blocked) {
            // 点击可能已触发请求，只核对阻断原因，不重复点击发送。
            throw interactionFailure(send,"发送操作未正常完成，本岗位不重复点击",blocked);
        }
    }

    private boolean prepareInteraction(Locator target,Supplier<Boolean> stop,long timeoutMs) {
        long deadline=System.nanoTime()+timeoutMs*1_000_000L;
        BossChatOverlay.Notice obstacle=null;
        while(System.nanoTime()<deadline) {
            if(stop!=null && Boolean.TRUE.equals(stop.get()))return false;
            checkBlockingNotices();
            if(handleQuotaReminders(deadline,stop)) {if(!pump())break;continue;}
            obstacle=BossChatOverlay.find(target);
            if(obstacle==null)return true;
            checkInteractionOverlay(obstacle);
            if(!pump())break;
        }
        checkBlockingNotices();
        throw new BossChatInteractionException(obstacle==null?"沟通提醒尚未处理完成，已跳过当前岗位":overlayReason(obstacle));
    }

    private BossChatInteractionException interactionFailure(Locator target,String fallback,PlaywrightException cause) {
        // 工作经历限制和每日额度提醒可能在点击等待期间才出现，保留它们原有的处理层级。
        checkBlockingNotices();
        var obstacle=BossChatOverlay.find(target);checkInteractionOverlay(obstacle);
        return new BossChatInteractionException(obstacle==null?fallback:overlayReason(obstacle),cause);
    }
    private static String overlayReason(BossChatOverlay.Notice obstacle) {
        String summary=obstacle.summary();
        return "Boss 弹窗仍遮挡聊天区域"+(summary.isBlank()?"，未识别到弹窗文案":"："+summary)+"；已跳过该岗位";
    }
    private static void requireNoVerification(BossChatOverlay.Notice obstacle) {
        if(obstacle!=null && obstacle.requiresUserAction())
            throw new IllegalStateException("Boss 弹窗要求安全验证或重新登录，已停止本次任务："+obstacle.summary());
    }

    private void checkInteractionOverlay(BossChatOverlay.Notice obstacle) {
        requireNoVerification(obstacle);
        if (obstacle != null && BossNewPositionDialog.matches(obstacle.text())) {
            requireRepeatChat();
            throw new BossJobBlockedException("Boss 在聊天操作期间要求确认沟通新职位，已跳过该岗位");
        }
    }

    private void requireRepeatChat() {
        if (!allowRepeatChat) {
            throw new BossJobBlockedException("Boss 提示已与该招聘者沟通过，当前账号关闭了重复沟通或未配置跟进消息");
        }
    }

    private List<Page> ownedPages() {
        List<Page> owned = new ArrayList<>();
        owned.add(origin);
        owned.addAll(popups);
        return owned;
    }

    private boolean handleNewPosition(long deadline, Supplier<Boolean> stop) {
        for (Page candidate : ownedPages()) {
            if (candidate.isClosed()) continue;
            Locator confirmation;
            try {
                confirmation = BossNewPositionDialog.findConfirmation(candidate);
            } catch (PlaywrightException navigating) {
                log.debug("检查新职位确认框时页面正在切换：{}", navigating.getMessage());
                continue;
            }
            if (confirmation == null) continue;
            requireRepeatChat();
            if (draftFilled || chatPage != null && candidate != chatPage) {
                throw new BossJobBlockedException("Boss 在聊天就绪后要求更换沟通职位，已跳过该岗位");
            }
            if (newPositionPage != null) return true;
            checkBlockingNotices();
            requireNoVerification(BossChatOverlay.find(confirmation));
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0 || stop != null && Boolean.TRUE.equals(stop.get())) return true;
            try {
                confirmation.click(new Locator.ClickOptions().setTimeout(Math.min(remaining, 1_000)));
            } catch (PlaywrightException failure) {
                checkBlockingNotices();
                requireNoVerification(BossChatOverlay.find(confirmation));
                throw new BossChatInteractionException("Boss 的「沟通新职位」确认未完成，已跳过该岗位", failure);
            }
            newPositionPage = candidate;
            String message = "已点击「沟通新职位」，等待聊天区域就绪";
            log.info(message);
            progress.accept(message);
            return true;
        }
        return false;
    }

    private boolean handleQuotaReminders(long deadline,Supplier<Boolean> stop) {
        boolean found=false;
        for(var candidate:ownedPages()) {
            if(candidate.isClosed())continue;
            try {
                var notice=BossQuotaReminder.find(candidate);
                if(notice==null){clearReminder(candidate);continue;}
                found=true;
                if(notice.counts().remaining()==0)throw new BossDailyLimitReachedException(notice.text());
                if(notice.confirm()==null || reminderClicks>=3 || stop!=null && Boolean.TRUE.equals(stop.get()))continue;
                var element=notice.message().elementHandle();if(element==null)continue;
                var previous=reminderAttempts.get(candidate);
                boolean same=false;
                if(previous!=null && previous.text().equals(notice.text()))try {
                    same=Boolean.TRUE.equals(element.evaluate("(node,old) => node === old",previous.message()));
                }catch(PlaywrightException stale){clearReminder(candidate);}
                if(same){element.dispose();continue;}
                clearReminder(candidate);reminderAttempts.put(candidate,new ReminderAttempt(element,notice.text()));
                long remaining=(deadline-System.nanoTime())/1_000_000L;if(remaining<=0)continue;
                if(stop!=null && Boolean.TRUE.equals(stop.get()))continue;
                checkDailyLimit();
                reminderClicks++;
                notice.confirm().click(new Locator.ClickOptions().setTimeout(Math.min(remaining,1_000)));
                String message="已自动确认沟通额度提醒：今日已沟通 "+notice.counts().communicated()+" 位，剩余 "+notice.counts().remaining()+" 次，继续当前岗位";
                log.info(message);progress.accept(message);
            }catch(PlaywrightException navigationError){found=true;log.debug("额度提醒处理期间页面切换，继续检查：{}",navigationError.getMessage());}
        }
        return found;
    }
    private void clearReminder(Page page) {
        var previous=reminderAttempts.remove(page);
        if(previous!=null)try{previous.message().dispose();}catch(PlaywrightException ignored){}
    }
    private boolean pump() {
        Page page=!origin.isClosed()?origin:popups.stream().filter(p -> !p.isClosed()).findFirst().orElse(null);
        if(page==null)return false;
        try{page.waitForTimeout(100);}catch(PlaywrightException navigationError){log.debug("等待聊天页时页面已切换：{}",navigationError.getMessage());}
        return true;
    }

    private Locator findConfirmation(Page page) {
        Locator greetConfirm = page.locator(GREET_CONFIRM);
        if (greetConfirm.count() == 1 && greetConfirm.isVisible()
                && "继续沟通".equals(greetConfirm.textContent().trim())) return greetConfirm;
        Locator stays = page.locator(STAY);
        for (int i = 0; i < stays.count(); i++) {
            Locator container = stays.nth(i).locator(CONFIRM_CONTAINER);
            Locator confirm = container.getByText("继续沟通", new Locator.GetByTextOptions().setExact(true));
            if (confirm.count() == 1 && confirm.isVisible()) return confirm;
        }
        return null;
    }

    @Override
    public void close() {
        origin.offPopup(popupListener);
        for(var page:new ArrayList<>(reminderAttempts.keySet()))clearReminder(page);
        // 只清理由当前岗位点击产生的新窗口，不影响其他招聘平台标签页。
        for (Page popup : popups) closePage(popup);
        closePage(origin);
    }

    private void closePage(Page page) {
        try {
            if (!page.isClosed()) page.close();
        } catch (PlaywrightException error) {
            log.debug("关闭本轮聊天页失败：{}", error.getMessage());
        }
    }
}
