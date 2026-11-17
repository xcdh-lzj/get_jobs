package com.getjobs.worker.boss;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** 仅识别“还剩 N 次沟通机会”的信息提醒，不确认登录、验证、付费或未知弹窗。 */
final class BossQuotaReminder {
    static final Pattern TEXT=Pattern.compile(
            "^\\s*(?:您|你)?\\s*(?:今天|今日|当日)\\s*已(?:经)?\\s*与\\s*([0-9０-９]{1,6})\\s*位\\s*BOSS\\s*沟通\\s*[,，。:：]?\\s*还(?:剩|有)\\s*([0-9０-９]{1,6})\\s*(?:次|个)\\s*(?:沟通\\s*)?(?:机会|名额)\\s*(?:哦|哟|啦|噢)?\\s*[~～!！。]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACK=Pattern.compile("^(?:好|确定|知道了|我知道了)$");
    private static final String CONTAINER="xpath=ancestor::*[not(self::body or self::html) and .//*[normalize-space(.)='好' or normalize-space(.)='确定' or normalize-space(.)='知道了' or normalize-space(.)='我知道了' or @value='好' or @value='确定']][1]";
    record Counts(int communicated,int remaining) {}
    record Notice(Locator message,Locator confirm,String text,Counts counts) {}
    private BossQuotaReminder() {}

    static Counts parse(String text) {
        var match=TEXT.matcher(Normalizer.normalize(text==null?"":text,Normalizer.Form.NFKC));
        return match.matches()?new Counts(Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2))):null;
    }
    static Notice find(Page page) {
        var messages=page.getByText(TEXT);
        for(int i=0;i<messages.count();i++) {
            var message=messages.nth(i);if(!message.isVisible())continue;
            String text=message.textContent();var counts=parse(text);if(counts==null)continue;
            boolean scoped=message.locator("xpath=ancestor::*[not(self::body or self::html) and (@role='dialog' or contains(@class,'dialog') or contains(@class,'modal'))][1]").count()>0;
            Locator confirm=null;var container=message.locator(CONTAINER);
            if(container.count()==1 && container.innerText().length()<=600) {
                // 不接受夹带购买、验证等其他文案的同名按钮，只确认当前信息提醒。
                String rest=Normalizer.normalize(container.innerText(),Normalizer.Form.NFKC)
                        .replaceAll("[\\s\\p{Z}]+","")
                        .replace(Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("[\\s\\p{Z}]+",""),"")
                        .replaceAll("温馨提示|沟通提醒|提示|我知道了|知道了|确定|好|[\\s\\p{Z}：:!！。×]","");
                if(rest.isEmpty()) {
                    scoped=true;
                    var button=container.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName(ACK));
                    if(button.count()==1 && button.isVisible())confirm=button;
                    else {
                        var link=container.locator("button,a,[role='button']").filter(new Locator.FilterOptions().setHasText(ACK));
                        if(link.count()==1 && link.isVisible())confirm=link;
                        else {
                            // 平台也可能用没有 ARIA 角色的节点渲染按钮；仍严格限定在已核对的提醒容器内。
                            var textButton=container.getByText(ACK);
                            if(textButton.count()==1 && textButton.isVisible())confirm=textButton;
                        }
                    }
                }
            }
            if(scoped)return new Notice(message,confirm,text.strip(),counts);
        }
        return null;
    }
}
