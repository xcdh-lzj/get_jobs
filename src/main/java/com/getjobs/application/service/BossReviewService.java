package com.getjobs.application.service;

import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.worker.boss.review.*;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

@Service
@RequiredArgsConstructor
public class BossReviewService {
    private final BossReviewStore store;
    private final BossJobReviewer reviewer;
    private final BossService boss;
    private final BossAccountLocks locks;
    private final BossHrSettingsService hrSettings;
    private final Map<Long,Preview> previews=new ConcurrentHashMap<>();
    private record Preview(String runId,AtomicBoolean stop) {}
    public record Options(BossReviewPolicy policy,boolean generateGreeting,BossReviewEconomy economy) {
        public Options(BossReviewPolicy policy,boolean generateGreeting){this(policy,generateGreeting,null);}
    }
    public record Context(BossReviewPolicy policy,BossJobReviewer.Filters filters,String fingerprint,BossReviewEconomy economy) {}

    public Options options() {
        var config=boss.getFirstConfig();
        return new Options(store.policy(),config!=null && Integer.valueOf(1).equals(config.getEnableAi()),store.economy());
    }
    @Transactional public void saveOptions(Options options) {
        synchronized (locks) {
            locks.requireEditable(BossAccountContext.id());
            if(options==null || options.policy()==null)throw new IllegalArgumentException("缺少审核设置");
            if(options.economy()!=null)options.economy().validated();
            store.savePolicy(options.policy().validated());
            if(options.economy()!=null)store.saveEconomy(options.economy());
            var patch=new BossConfigEntity(); patch.setEnableAi(options.generateGreeting()?1:0);
            boss.saveOrUpdateFirstSelective(patch);
        }
    }
    public Context context(BossReviewPolicy override,boolean forceEnabled) {
        BossReviewPolicy policy=(override==null?store.policy():override).validated();
        if(forceEnabled)policy=policy.forceEnabled();
        var config=boss.getFirstConfig();
        List<String> cities=List.of(),experience=List.of(),degrees=List.of();
        Double min=null,max=null;
        var salary=com.getjobs.worker.boss.BossSalaryPolicy.of(config).selection(config==null?null:config.getSalary(),code -> {
            var option=boss.getOptionByTypeAndCode("salary",code);return option==null?null:option.getName();
        });
        if(config!=null) {
            cities=unrestricted(boss.toNames("city",boss.parseListString(config.getCityCode())));
            experience=unrestricted(boss.toNames("experience",boss.parseListString(config.getExperience())));
            degrees=unrestricted(boss.toNames("degree",boss.parseListString(config.getDegree())));
            min=salary.minimumK();max=salary.maximumK();
        }
        var filters=new BossJobReviewer.Filters(cities,experience,degrees,min,max,
                Set.copyOf(boss.getBlackCompanies()),Set.copyOf(boss.getBlackJobs()),Set.copyOf(boss.getBlackRecruiters()),
                config!=null && Integer.valueOf(1).equals(config.getFilterDeadHr()),hrSettings==null?3:hrSettings.policy().maxDays(),salary);
        var economy=store.economy();
        // 模型只接收目标、方向、标题与 JD；基础条件每次重新核对，不让 HR 活跃度或切城使语义缓存失效。
        String hash=BossReviewStore.hash(BossJobReviewer.VERSION+"|"+reviewer.modelIdentity()+"|"+economy.enabled()+"|"
                +store.write(List.of(policy.goal(),policy.targetRoles())));
        return new Context(policy,filters,hash,economy);
    }
    private List<String> unrestricted(List<String> values) {
        return values.stream().anyMatch(v -> Set.of("0","100010000","全国","不限","经验不限","学历不限").contains(v))
                ? List.of():values.stream().filter(v->!v.isBlank()).toList();
    }
    public Context forCity(Context original,String city) {
        var f=original.filters();
        var scoped=new BossJobReviewer.Filters(unrestricted(List.of(city)),f.experience(),f.degrees(),f.minMonthlyK(),f.maxMonthlyK(),f.companies(),f.jobTitles(),f.recruiterTitles(),f.filterInactive(),f.maxHrDays(),f.salarySelection());
        return new Context(original.policy(),scoped,original.fingerprint(),original.economy());
    }
    public String begin(String mode,Context context) { return store.begin(mode,context.policy(),context.economy()); }
    public void finish(String runId,String state,String error) { store.finish(runId,state,error); }
    public BossReviewResult preScreen(BossReviewJob job,Context context) { return reviewer.preScreen(job,context.policy(),context.filters()); }
    public void record(String runId,Context context,BossReviewResult result) { store.save(runId,result,context.fingerprint(),false);store.cacheJob(runId,result.job()); }
    public void delivery(String runId,BossReviewJob job,String state) { store.delivery(runId,job,state); }
    public Optional<BossReviewJob> cachedJob(String runId,BossReviewJob job) { return store.cachedJob(runId,job); }

    public void analyze(String runId,Context context,List<BossReviewJob> jobs,Supplier<Boolean> stop,Consumer<BossReviewResult> observer) {
        List<BossReviewJob> pending=new ArrayList<>();
        for(var job:jobs) {
            if(stop.get())return;
            var basic=reviewer.preScreen(job,context.policy(),context.filters(),true);
            if(basic!=null) { record(runId,context,basic); observer.accept(basic); continue; }
            var cached=store.cachedResult(job,context.fingerprint(),context.economy().cacheDays());
            if(cached.isPresent()) {
                store.save(runId,cached.get(),context.fingerprint(),true); observer.accept(cached.get());
            } else pending.add(job);
        }
        var usage=store.usage(runId);
        reviewer.review(pending,context.policy(),stop,result -> { record(runId,context,result); observer.accept(result); },
                new BossJobReviewer.Execution(context.economy(),usage.totalTokens(),usage.unreportedRequests()>0,event -> store.recordUsage(runId,event)));
    }
    public String preview(String source,int limit,BossReviewPolicy draft) {
        if(source==null || !Set.of("examples","history").contains(source))throw new IllegalArgumentException("请选择示例或历史岗位");
        if(limit<1 || limit>30)throw new IllegalArgumentException("一次试审 1 至 30 条岗位");
        long account=BossAccountContext.id();
        if(locks.busy(account))throw new ResponseStatusException(HttpStatus.CONFLICT,"该账号正在投递或等待队列执行，请先停止后试审");
        Context configured=context(draft,true);
        Context context="examples".equals(source) ? new Context(configured.policy(),BossJobReviewer.Filters.none(),
                configured.fingerprint(),configured.economy()) : configured;
        synchronized (previews) {
            if(previews.containsKey(account))throw new ResponseStatusException(HttpStatus.CONFLICT,"该账号已有试审任务，请等待或停止后重试");
            String runId=begin(source,context);
            var handle=new Preview(runId,new AtomicBoolean());previews.put(account,handle);
            try {
                CompletableFuture.runAsync(() -> {
                    try(var scope=BossAccountContext.use(account)) {
                        var jobs="examples".equals(source)?examples().stream().limit(limit).toList():store.history(limit);
                        LinkedHashMap<String,BossReviewJob> unique=new LinkedHashMap<>();jobs.forEach(j->unique.putIfAbsent(j.key(),j));
                        analyze(runId,context,List.copyOf(unique.values()),handle.stop()::get,ignored -> {});
                        finish(runId,handle.stop().get()?"stopped":"completed","");
                    } catch(BossReviewBudgetReachedException budget) {
                        try(var scope=BossAccountContext.use(account)) { finish(runId,handle.stop().get()?"stopped":"budget_reached",budget.getMessage()); }
                    } catch(Exception error) {
                        try(var scope=BossAccountContext.use(account)) { finish(runId,"failed","审核执行失败，请检查 AI 配置或后端日志"); }
                    } finally { previews.remove(account,handle); }
                });
            } catch(RuntimeException error) { previews.remove(account,handle);finish(runId,"failed","无法启动试审");throw error; }
            return runId;
        }
    }
    public void stopPreview(String runId) {
        var preview=previews.get(BossAccountContext.id());
        if(preview==null || !preview.runId().equals(runId))throw new ResponseStatusException(HttpStatus.CONFLICT,"该试审任务已结束或不属于当前账号");
        preview.stop().set(true);
    }
    public Optional<BossReviewStore.Run> results(String runId,String decision) {
        return runId==null || runId.isBlank()?store.latest(decision):store.run(runId,decision);
    }
    @PreDestroy public void shutdown() { previews.values().forEach(p->p.stop().set(true)); }

    public static List<BossReviewJob> examples() {
        return List.of(
                example("example-agent","AI Agent 开发工程师","负责使用 Python 开发 AI Agent 后端服务，集成工具调用、多智能体工作流和 RAG 检索，完成接口编码、测试和部署。"),
                example("example-sales","AI 大模型销售经理","负责客户拓展、商务谈判、合同签约，完成销售业绩目标。了解大模型，熟悉 Python 者优先。"),
                example("example-product","AI 产品经理","负责用户调研、产品规划、PRD 编写与产品路线图，协调研发团队交付，不承担代码开发工作。"),
                example("example-python","Python 后端工程师","负责公司大模型业务应用的后端研发，使用 Python 开发 RAG 知识库问答、Agent 工具调用与业务 API，承担服务测试和上线。"),
                example("example-solution","AI 解决方案工程师","负责客户拓展、方案宣讲、售前演示及招投标材料，推动客户签约并完成销售转化。熟悉大模型及 Python 为加分项。"),
                example("example-product-dev","AI 产品研发工程师","负责 AI 产品的代码研发，使用大模型 API 开发 Agent 服务、RAG 检索和工具调用，完成后端接口开发及部署。"),
                example("example-research","大模型算法研究员","主要负责基础大模型预训练、训练算法优化和论文研究，不承担 Agent、RAG 或大模型业务应用的代码研发。"),
                example("example-missing","AI 应用工程师",""));
    }
    private static BossReviewJob example(String id,String title,String description) {
        return new BossReviewJob(id,title,"示例岗位（非真实招聘）","","","","","","",description,"","示例");
    }
}
