package com.getjobs.worker.service;

import com.getjobs.application.service.*;
import com.getjobs.application.entity.AiEntity;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.boss.BossDailyLimitReachedException;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.BossBrowserSessions;
import com.getjobs.worker.manager.PlaywrightManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Boss 单账号任务和顺序队列共用唯一执行入口。重启不会自动恢复投递。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BossJobService implements JobPlatformService {
    private static final String PLATFORM = "boss";
    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Boss> bossProvider;
    private final BossAccountService accounts;
    private final BossBrowserSessions browsers;
    private final BossAccountLocks locks;
    private final Map<Long, BossProgressTracker> progresses = new ConcurrentHashMap<>();
    private final String instanceId = UUID.randomUUID().toString();
    private long revision;
    private boolean busy;
    private volatile boolean stopRequested;
    private String queueState = "idle", mode = "single", queueMessage = "尚未启动队列";
    private Long activeAccountId;
    private List<QueueItem> items = new ArrayList<>();
    private Consumer<JobProgressMessage> callback = ignored -> {};

    public record QueueItem(long accountId, String accountName, String state, int deliveredCount, String message) {}
    public record QueueSnapshot(String instanceId, long revision, String mode, String state, boolean isRunning,
                                Long activeAccountId, String message, List<QueueItem> items) {}
    private BossProgressTracker progress(long id) { return progresses.computeIfAbsent(id, ignored -> new BossProgressTracker()); }

    public boolean startDelivery(Consumer<JobProgressMessage> callback) {
        return startDelivery(BossAccountContext.id(), callback);
    }
    public boolean startDelivery(long accountId, Consumer<JobProgressMessage> callback) {
        return start(List.of(accountId), false, callback, true);
    }
    public boolean startReviewOnly(long accountId, Consumer<JobProgressMessage> callback) {
        return start(List.of(accountId), false, callback, true, true);
    }
    public boolean startQueue(List<Long> accountIds, Consumer<JobProgressMessage> callback) {
        return start(accountIds, true, callback, true);
    }
    @Override public void executeDelivery(Consumer<JobProgressMessage> callback) {
        if (!start(List.of(BossAccountContext.id()), false, callback, false))
            callback.accept(JobProgressMessage.warning(PLATFORM,"Boss 任务已在运行中"));
    }

    private boolean start(List<Long> ids, boolean queue, Consumer<JobProgressMessage> nextCallback, boolean async) {
        return start(ids, queue, nextCallback, async, false);
    }
    private boolean start(List<Long> ids, boolean queue, Consumer<JobProgressMessage> nextCallback, boolean async, boolean reviewOnly) {
        synchronized (this) { if (busy) return false; }
        if (ids==null || ids.isEmpty() || ids.size()>20 || ids.stream().anyMatch(Objects::isNull)
                    || ids.stream().distinct().count()!=ids.size())
                throw new IllegalArgumentException("请选择 1 至 20 个不同的 Boss 账号，并按执行顺序排列");
        locks.reserve(ids);
        synchronized (this) {
            try {
                var next = new ArrayList<QueueItem>();
                for (long id : ids) {
                    var account = accounts.require(id);
                    try (var scope = BossAccountContext.use(id)) {
                        BossConfig config = configService.getBossConfig();
                        if (config==null || config.getKeywords()==null || config.getKeywords().stream().noneMatch(k -> k!=null && !k.isBlank())
                                || config.getCityCode()==null || config.getCityCode().isEmpty())
                            throw new IllegalArgumentException("账号“"+account.name()+"”尚未配置搜索关键词或城市");
                    }
                    next.add(new QueueItem(id,account.name(),"pending",0,"等待执行"));
                }
                items=next; mode=reviewOnly?"review_only":queue?"queue":"single"; callback=nextCallback;
                busy=true; stopRequested=false; queueState="running"; queueMessage="按列表顺序执行";
                prepare(0);
            } catch (RuntimeException e) { locks.release(); throw e; }
        }
        publish(JobProgressMessage.info(PLATFORM,queue?"Boss 账号队列已启动":"Boss 任务已启动"));
        if (async) {
            try { CompletableFuture.runAsync(this::executeReserved); }
            catch (RuntimeException e) { abortStartup(e); throw e; }
        } else executeReserved();
        return true;
    }

    /** 只在持有本服务锁时调用，保证停止请求和启动下一个账号不能交错。 */
    private void prepare(int index) {
        QueueItem item=items.get(index);
        activeAccountId=item.accountId();
        String taskId=progress(item.accountId()).begin();
        if (taskId==null) throw new IllegalStateException("账号任务未完全结束");
        items.set(index,new QueueItem(item.accountId(),item.accountName(),"running",0,"准备执行"));
        revision++;
    }
    private void executeReserved() {
        String finalState="completed";
        try {
            for (int i=0; i<items.size(); i++) {
                QueueItem item;
                synchronized (this) {
                    if (stopRequested) { finalState="stopped"; break; }
                    item=items.get(i);
                }
                runDelivery(item.accountId(),progress(item.accountId()).snapshot().taskId());
                synchronized (this) {
                    var snapshot=progress(item.accountId()).snapshot();
                    items.set(i,new QueueItem(item.accountId(),item.accountName(),snapshot.state(),snapshot.deliveredCount(),snapshot.message()));
                    revision++;
                    if (stopRequested || "stopped".equals(snapshot.state())) { finalState="stopped"; break; }
                    if ("failed".equals(snapshot.state()) || "budget_reached".equals(snapshot.state())) { finalState=snapshot.state(); break; }
                    if (i+1<items.size()) prepare(i+1);
                }
                publish(JobProgressMessage.info(PLATFORM,"账号“"+item.accountName()+"”执行结束"));
            }
        } catch (Exception e) {
            log.error("Boss 队列执行失败",e);
            finalState="failed";
        } finally {
            synchronized (this) {
                if (stopRequested) finalState="stopped";
                for (int i=0;i<items.size();i++) {
                    QueueItem item=items.get(i);
                    if ("pending".equals(item.state()) || "running".equals(item.state())) {
                        var tracker=progress(item.accountId());
                        if (tracker.isRunning()) tracker.finish(tracker.snapshot().taskId(),finalState,tracker.snapshot().deliveredCount());
                        String state="running".equals(item.state()) ? finalState : "cancelled";
                        items.set(i,new QueueItem(item.accountId(),item.accountName(),state,"pending".equals(item.state())?0:tracker.snapshot().deliveredCount(),"队列已结束，未继续执行"));
                    }
                }
                busy=false; activeAccountId=null; queueState=finalState;
                long limited=items.stream().filter(item -> "quota_reached".equals(item.state())).count();
                queueMessage=switch(finalState) {
                    case "completed" -> "review_only".equals(mode) ? "岗位采集与审核完成（未投递）" : limited==0 ? "所有账号执行完成"
                            : "single".equals(mode) ? "当前账号今日沟通已达上限，本轮已结束"
                            : "队列执行结束，"+limited+" 个账号今日沟通已达上限";
                    case "stopped" -> "队列已停止，后续账号未启动";
                    case "budget_reached" -> "岗位审核 token 预算已触发，后续城市和账号未启动；请核对用量后再运行";
                    default -> "账号执行失败，后续账号未启动；处理后可重新选择账号运行";
                };
                revision++; locks.release();
            }
            publish(JobProgressMessage.info(PLATFORM,queueMessage));
        }
    }
    private void abortStartup(RuntimeException error) {
        synchronized (this) {
            if (activeAccountId!=null) {
                var p=progress(activeAccountId); p.finish(p.snapshot().taskId(),"failed",0);
            }
            busy=false; activeAccountId=null; queueState="failed"; queueMessage=error.getMessage(); revision++; locks.release();
        }
        publish(JobProgressMessage.error(PLATFORM,"启动失败"));
    }

    private void runDelivery(long accountId, String id) {
        BossProgressTracker progress=progress(accountId);
        int delivered=0;
        String errorMessage=null;
        String quotaNotice=null;
        String budgetNotice=null;
        boolean monitoringPaused=false;
        try (var scope=BossAccountContext.use(accountId)) {
            emit(accountId,id,JobProgressMessage.info(PLATFORM,"检查账号浏览器状态..."));
            if (shouldStop()) return;
            var page=browsers.ready(accountId,false);
            if (shouldStop()) return;
            if (!browsers.loggedIn(accountId)) throw new IllegalStateException("账号登录已失效，请先打开该账号浏览器登录");
            if (accountId==1) { playwrightManager.pauseBossMonitoring(); monitoringPaused=true; }
            BossConfig config=configService.getBossConfig();
            Boss boss=bossProvider.getObject();
            boss.setPage(page); boss.setConfig(config); boss.setShouldStopCallback(this::shouldStop);
            var profile=accounts.profile(accountId);
            AiEntity ai=new AiEntity(); ai.setIntroduce(profile.introduce()); ai.setPrompt(profile.prompt()); boss.setAiConfig(ai);
            boss.setProgressCallback(new Boss.ProgressCallback() {
                @Override public void accept(String message,Integer current,Integer total) {
                    emit(accountId,id,current!=null && total!=null ? JobProgressMessage.progress(PLATFORM,message,current,total) : JobProgressMessage.info(PLATFORM,message));
                }
                @Override public void searchStarted(String city,String keyword) {
                    progress.search(id,city,keyword);
                    emit(accountId,id,JobProgressMessage.info(PLATFORM,"正在搜索岗位："+city+" · "+keyword));
                }
                @Override public void jobStarted(String name,String company,int total) { progress.job(id,name,company,total); }
                @Override public void cityProgress(com.getjobs.worker.boss.BossCityProgress value){progress.cities(id,value);}
                @Override public void jobProcessed(boolean sent,int current,int total) {
                    progress.processed(id,sent,current,total);
                    emit(accountId,id,JobProgressMessage.progress(PLATFORM,sent?"当前岗位投递流程完成":"当前岗位未投递",current,total));
                }
            });
            boss.setReviewOnly("review_only".equals(mode));
            boss.prepare(); progress.running(id);
            emit(accountId,id,JobProgressMessage.info(PLATFORM,"review_only".equals(mode)?"开始岗位采集与审核（不投递）...":"开始投递任务..."));
            delivered=playwrightManager.callOnPlaywright(() -> {
                try (var workerScope=BossAccountContext.use(accountId)) { return boss.execute(); }
            });
            browsers.save(accountId);
        } catch (com.getjobs.worker.boss.review.BossReviewBudgetReachedException budget) {
            budgetNotice=budget.getMessage();
            log.info("Boss 账号 {} 审核预算触发：{}",accountId,budgetNotice);
        } catch (BossDailyLimitReachedException limit) {
            quotaNotice=limit.getMessage();
            log.info("Boss 账号 {} 今日沟通已达上限，结束当前账号：{}",accountId,quotaNotice);
        } catch (Exception e) {
            log.error("Boss 账号 {} 投递任务执行失败",accountId,e);
            errorMessage=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
        } finally {
            if (monitoringPaused) playwrightManager.resumeBossMonitoring();
            String state=shouldStop()?"stopped":budgetNotice!=null?"budget_reached":quotaNotice!=null?"quota_reached":errorMessage!=null?"failed":"completed";
            if (errorMessage!=null || quotaNotice!=null || budgetNotice!=null) delivered=progress.snapshot().deliveredCount();
            String message=shouldStop()?"投递任务已停止":budgetNotice!=null?budgetNotice:quotaNotice!=null
                    ? "今日沟通已达上限，已结束当前账号投递，本次任务共记录 "+delivered+" 次投递"
                    :errorMessage!=null?"投递结束: "+errorMessage:"review_only".equals(mode)?"岗位采集与审核完成，未发起投递":"投递任务完成，共记录 "+delivered+" 次投递";
            progress.finish(id,state,delivered);
            emit(accountId,id,"failed".equals(state)?JobProgressMessage.error(PLATFORM,message)
                    :"stopped".equals(state) || "quota_reached".equals(state) || "budget_reached".equals(state)
                    ?JobProgressMessage.warning(PLATFORM,message):JobProgressMessage.success(PLATFORM,message));
        }
    }
    private void emit(long accountId,String id,JobProgressMessage message) {
        synchronized (this) {
            var tracker=progress(accountId);
            if (!tracker.owns(id)) return;
            tracker.record(message); revision++;
        }
        publish(message);
    }
    private void publish(JobProgressMessage message) {
        Consumer<JobProgressMessage> listener;
        synchronized (this) { listener=callback; }
        try { listener.accept(message); } catch (RuntimeException e) { log.warn("推送 Boss 进度失败，任务继续运行",e); }
    }
    @Override public void stopDelivery() {
        Long account;
        synchronized (this) {
            if (!busy) return;
            stopRequested=true; queueState="stopping"; queueMessage="等待当前操作结束，后续账号不会启动";
            account=activeAccountId;
            if (account!=null) progress(account).requestStop();
            revision++;
        }
        publish(JobProgressMessage.warning(PLATFORM,"已请求停止整个队列，等待当前操作结束..."));
    }
    public synchronized QueueSnapshot getQueue() {
        var currentItems=items.stream().map(item -> {
            var p=progress(item.accountId()).snapshot();
            return "running".equals(item.state()) ? new QueueItem(item.accountId(),item.accountName(),p.state(),p.deliveredCount(),p.message()) : item;
        }).toList();
        return new QueueSnapshot(instanceId,revision,mode,queueState,busy,activeAccountId,queueMessage,currentItems);
    }
    @Override public Map<String,Object> getStatus() { return getStatus(BossAccountContext.id()); }
    public synchronized Map<String,Object> getStatus(long accountId) {
        var snapshot=progress(accountId).snapshot();
        Map<String,Object> status=new HashMap<>();
        status.put("platform",PLATFORM); status.put("accountId",accountId);
        status.put("isRunning",snapshot.isRunning()); status.put("isLoggedIn",browsers.loggedIn(accountId));
        status.put("progress",snapshot); status.put("busy",busy); status.put("locked",locks.busy(accountId)); status.put("queue",getQueue());
        return status;
    }
    public long runningForMillis() { return progress(BossAccountContext.id()).snapshot().elapsedMillis(); }
    @Override public synchronized boolean isRunning() { return busy; }
    @Override public String getPlatformName() { return PLATFORM; }
    public boolean shouldStop() { return stopRequested; }
    @jakarta.annotation.PreDestroy public void shutdown() { stopDelivery(); }
}
