package com.getjobs.worker.boss.review;

import com.getjobs.worker.boss.BossHrActivityReader;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.json.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** 监听页面自身的列表响应并批量读取卡片；滚动时只采集，不调用 AI。 */
public final class BossCandidateCollector implements AutoCloseable {
    private final Page page;
    private final String keyword;
    private final LinkedHashMap<String,BossReviewJob> jobs=new LinkedHashMap<>();
    private final Queue<Response> responses=new ConcurrentLinkedQueue<>();
    private final Consumer<Response> listener=response -> {
        String url=response.url().toLowerCase(Locale.ROOT);
        if(url.contains("zhipin.com/wapi/") && (url.contains("joblist") || url.contains("recommend"))) responses.add(response);
    };
    public BossCandidateCollector(Page page,String keyword) { this.page=page;this.keyword=keyword;page.onResponse(listener); }
    public void collect() {
        drain();
        try {
            Object raw=page.evaluate("""
                    () => JSON.stringify(Array.from(document.querySelectorAll('ul.rec-job-list li.job-card-box, ul.job-list-box li.job-card-wrapper')).map(card => {
                      const text = selectors => { for (const s of selectors.split(',')) { const el=card.querySelector(s.trim()); if(el?.textContent?.trim()) return el.textContent.trim(); } return ''; };
                      const link=card.querySelector('a.job-name[href],a[href*="/job_detail/"]');
                      const tags=Array.from(card.querySelectorAll('ul.tag-list li,.job-labels span')).map(x=>x.textContent.trim());
                      return { encryptJobId:card.dataset.jobId || card.dataset.encryptJobId || '',
                        jobName:text('a.job-name,.job-name,.job-title,.job-title-text'), brandName:text('.company-name,.company-title'),
                        cityName:text('.job-area,.job-location,span.company-location'), salaryDesc:text('.salary,.salary-text,.job-salary'),
                        jobExperience:tags.find(x=>/经验|年|应届|在校/.test(x)) || '', jobDegree:tags.find(x=>/本科|硕士|博士|大专|学历/.test(x)) || '',
                        bossActiveTimeDesc:text('.boss-active-time,.boss-active-status,.boss-online-tag'),
                        jobUrl:link?.getAttribute('href') || '' };
                    }))
                    """);
            if(raw instanceof String text) for(Object row:new JSONArray(text)) if(row instanceof JSONObject value) add(fromList(value,keyword));
        } catch(RuntimeException ignored) { /* 页面导航期间暂时无法读取，下一次滚动继续采集。 */ }
    }
    private void drain() {
        Response response;
        while((response=responses.poll())!=null) try {
            if(response.status()!=200)continue;
            String body=response.text(); if(body.length()>4_000_000)continue;
            JSONObject data=new JSONObject(body).optJSONObject("zpData"); if(data==null)continue;
            for(String field:List.of("jobList","jobCardList","list")) {
                JSONArray list=data.optJSONArray(field);if(list==null)continue;
                for(Object row:list) if(row instanceof JSONObject job && job.has("jobName")) add(fromList(job,keyword));
            }
        } catch(RuntimeException ignored) { /* 不猜测缺失字段，缺少完整JD的岗位会补详情或留待审。 */ }
    }
    public List<BossReviewJob> jobs() { collect();return List.copyOf(jobs.values()); }
    public void add(BossReviewJob job) {
        if(job.title().isBlank())return;
        String key=job.key();
        BossReviewJob incoming=job;
        // DOM 卡片有时没有岗位ID，只在标题、公司能唯一对应时与响应记录合并。
        if(job.id().isBlank() && !job.company().isBlank()) {
            var matching=jobs.entrySet().stream().filter(e -> e.getValue().title().equals(job.title()) && e.getValue().company().equals(job.company())
                    && (job.city().isBlank() || e.getValue().city().isBlank() || e.getValue().city().equals(job.city()))).toList();
            if(matching.size()==1)key=matching.getFirst().getKey();
        }
        if(!job.id().isBlank() && !job.company().isBlank() && !jobs.containsKey(key)) {
            var unknown=jobs.entrySet().stream().filter(e -> e.getValue().id().isBlank() && e.getValue().detailUrl().isBlank()
                    && e.getValue().title().equals(job.title()) && e.getValue().company().equals(job.company())
                    && (job.city().isBlank() || e.getValue().city().isBlank() || e.getValue().city().equals(job.city()))).toList();
            if(unknown.size()==1)incoming=merge(jobs.remove(unknown.getFirst().getKey()),job);
        }
        BossReviewJob old=jobs.get(key);
        jobs.put(key,old==null?incoming:merge(old,incoming));
    }
    private static BossReviewJob merge(BossReviewJob a,BossReviewJob b) {
        return new BossReviewJob(or(b.id(),a.id()),or(b.title(),a.title()),or(b.company(),a.company()),or(b.city(),a.city()),or(b.salary(),a.salary()),
                or(b.experience(),a.experience()),or(b.degree(),a.degree()),or(b.recruiterTitle(),a.recruiterTitle()),or(b.activeStatus(),a.activeStatus()),
                or(b.description(),a.description()),or(b.detailUrl(),a.detailUrl()),or(b.keyword(),a.keyword()));
    }
    private static String or(String first,String fallback) { return first.isBlank()?fallback:first; }
    public static BossReviewJob fromList(JSONObject row,String keyword) {
        String id=row.optString("encryptJobId",row.optString("encryptId",""));
        String url=BossReviewJob.safeDetailUrl(row.optString("jobUrl",""));
        if(id.isBlank() && !url.isBlank()) id=idFromUrl(url);
        if(url.isBlank() && id.matches("[A-Za-z0-9_~=-]{5,180}")) url="https://www.zhipin.com/job_detail/"+id+".html";
        return new BossReviewJob(id,row.optString("jobName",""),row.optString("brandName",""),row.optString("cityName",""),row.optString("salaryDesc",""),
                row.optString("jobExperience",""),row.optString("jobDegree",""),row.optString("bossTitle",""),BossHrActivityReader.fromJson(row),
                // 列表中的描述可能只是摘要；候选必须从详情页补齐 JD 后再审核。
                "",url,keyword,row.optString("brandScaleName",""));
    }
    public static String idFromUrl(String value) {
        try { String path=URI.create(value).getPath();String file=path.substring(path.lastIndexOf('/')+1);return file.endsWith(".html")?file.substring(0,file.length()-5):file; }
        catch(RuntimeException ignored) {return "";}
    }
    @Override public void close() { page.offResponse(listener); responses.clear(); }
}
