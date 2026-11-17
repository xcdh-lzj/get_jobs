package com.getjobs.worker.boss;

import com.getjobs.worker.boss.review.*;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.json.JSONObject;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

/** 仅打开岗位详情读取JD，不查找或点击“立即沟通”。 */
final class BossReviewDetails {
    record Detail(BossReviewJob job,String error) {}
    private BossReviewDetails() {}
    static Detail load(Page listing,BossReviewJob basic) {
        return load(listing,basic,true);
    }
    static Detail load(Page listing,BossReviewJob basic,boolean requireHr) {
        if(basic.detailUrl().isBlank()) return new Detail(basic,"未获取到可核对的岗位详情链接，已自动跳过");
        Page detail=PlaywrightUtil.newBackgroundPage(listing);
        try {
            AtomicReference<Response> response=new AtomicReference<>();
            detail.onResponse(r -> { if(r.url().contains("/wapi/zpgeek/job/detail.json") && r.status()==200)response.set(r); });
            detail.navigate(basic.detailUrl(),new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000));
            BossCommunicationLimit.check(detail);
            if(!URI.create(detail.url()).getPath().equals(URI.create(basic.detailUrl()).getPath()))
                return new Detail(basic,"详情页面发生跳转，无法核对岗位身份，已自动跳过");
            try { detail.waitForSelector(".job-sec-text",new Page.WaitForSelectorOptions().setTimeout(8_000)); }
            catch(TimeoutError ignored) { /* 某些版式没有此节点，仍尝试读正常详情响应。 */ }
            BossReviewJob full=basic;
            if(response.get()!=null) {
                JSONObject data=new JSONObject(response.get().text()).optJSONObject("zpData");
                JSONObject info=data==null?null:data.optJSONObject("jobInfo");
                JSONObject company=data==null?null:data.optJSONObject("brandComInfo");
                JSONObject boss=data==null?null:data.optJSONObject("bossInfo");
                if(info!=null) {
                    String id=info.optString("encryptId",basic.id());
                    if(!basic.id().isBlank() && !id.isBlank() && !id.equals(basic.id()))
                        return new Detail(basic,"详情岗位ID不一致，未发起沟通");
                    full=new BossReviewJob(id,info.optString("jobName",basic.title()),company==null?basic.company():company.optString("brandName",basic.company()),
                            info.optString("locationName",basic.city()),info.optString("salaryDesc",basic.salary()),info.optString("experienceName",basic.experience()),
                            info.optString("degreeName",basic.degree()),boss==null?basic.recruiterTitle():boss.optString("title",basic.recruiterTitle()),
                            activityOr(BossHrActivityReader.fromJson(boss),basic.activeStatus()),info.optString("postDescription",""),basic.detailUrl(),basic.keyword());
                }
            }
            if(full.description().isBlank()) {
                Locator description=detail.locator(".job-sec-text");
                if(description.count()>0) full=full.withDescription(description.allTextContents().stream().filter(s->!s.isBlank()).reduce((a,b)->a+"\n"+b).orElse(""));
            }
            if(BossSalary.parse(full.salary())==null) {
                // 接口未提供可用薪资时，补读本岗位详情页主标题区；不使用推荐岗位卡片的薪资。
                Locator salary=detail.locator(".job-banner .salary, .job-banner .salary-text, .job-banner .job-salary, .info-primary .salary, .info-primary .salary-text, .info-primary .job-salary");
                for(String text:salary.allTextContents()) {
                    if(BossSalary.parse(text)!=null) { full=full.withSalary(text);break; }
                }
            }
            full=full.withActivity(BossHrActivityReader.readWithFallback(detail,full.activeStatus(),requireHr));
            return new Detail(full,full.description().isBlank()?"未读取到完整岗位描述，已自动跳过":"");
        } catch(BossDailyLimitReachedException limit) { throw limit; }
        catch(RuntimeException error) { return new Detail(basic,"岗位详情读取失败或需要登录验证，未发起沟通"); }
        finally { try { if(!detail.isClosed())detail.close(); } catch(RuntimeException ignored) { /* 不覆盖停止或额度上限等原始结果。 */ } }
    }
    private static String activityOr(String value,String fallback) { return value.isBlank()?fallback:value; }
}
