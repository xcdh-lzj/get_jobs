package com.getjobs.worker.boss;

import com.getjobs.worker.boss.review.*;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.json.JSONObject;
import java.net.URI;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** 仅打开岗位详情读取JD，不查找或点击“立即沟通”。 */
final class BossReviewDetails {
    private static final Pattern COMPANY_SCALE = Pattern.compile(
            "^(?:(?:公司)?规模[:：]?)?((?:\\d+(?:-\\d+)?人(?:以上|以下)?|(?:少于|超过|不足)\\d+人))$");
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
                            valueOr(BossHrActivityReader.fromJson(boss),basic.activeStatus()),info.optString("postDescription",""),
                            basic.detailUrl(),basic.keyword(),valueOr(company==null?"":company.optString("scaleName",""),basic.companyScale()));
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
            if (full.companyScale().isBlank()) full = full.withCompanyScale(readCompanyScale(detail));
            return new Detail(full,full.description().isBlank()?"未读取到完整岗位描述，已自动跳过":"");
        } catch(BossDailyLimitReachedException limit) { throw limit; }
        catch(RuntimeException error) { return new Detail(basic,"岗位详情读取失败或需要登录验证，未发起沟通"); }
        finally { try { if(!detail.isClosed())detail.close(); } catch(RuntimeException ignored) { /* 不覆盖停止或额度上限等原始结果。 */ } }
    }
    private static String valueOr(String value,String fallback) { return value.isBlank()?fallback:value; }

    private static String readCompanyScale(Page detail) {
        try {
            Object value = detail.evaluate("""
                    () => {
                      const heading = '公司基本信息';
                      const visible = element => element.getClientRects().length > 0 && getComputedStyle(element).visibility === 'visible';
                      const companyPanel = '.sider-company,.job-detail-company,.company-info';
                      const otherContent = '.job-sec-text,.job-recommend,.job-recommend-main,.similar-job,.recommend-list';
                      const labels = Array.from(document.querySelectorAll('h1,h2,h3,h4,p,dt,div,span'))
                        .filter(label => label.textContent.trim() === heading && visible(label) && !label.closest(otherContent));
                      const scoped = labels.filter(label => label.closest(companyPanel));
                      if (scoped.length) return scoped.flatMap(readPanel);
                      return labels.flatMap(readPanel);

                      function readPanel(label) {
                        let panel = label.closest(companyPanel) ?? label.parentElement;
                        if (panel?.innerText.trim() === heading) panel = panel.parentElement;
                        if (!panel || panel.matches('body,html')) return [];
                        const lines = panel.innerText.split('\\n').map(line => line.trim()).filter(Boolean);
                        const index = lines.indexOf(heading);
                        if (index < 0) return [];
                        const end = lines.findIndex((line, i) => i > index && /^(推荐岗位|相似岗位|相似职位|职位描述)$/.test(line));
                        return lines.slice(index + 1, end < 0 ? undefined : end);
                      }
                    }
                    """);
            var matches = new LinkedHashSet<String>();
            if (value instanceof List<?> lines) {
                for (Object line : lines) {
                    if (!(line instanceof String text)) continue;
                    String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                            .replaceAll("[\\s\\p{Z}]+", "").replaceAll("[–—~～至]", "-");
                    var match = COMPANY_SCALE.matcher(normalized);
                    if (match.matches()) matches.add(match.group(1));
                }
            }
            return matches.size() == 1 ? matches.iterator().next() : "";
        } catch (RuntimeException ignored) { return ""; }
    }
}
