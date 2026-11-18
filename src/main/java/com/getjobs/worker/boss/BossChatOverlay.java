package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import org.json.JSONObject;
import java.text.Normalizer;

/** 只读命中测试：只有实际遮挡目标控件的可见弹窗才算阻断，不点击未知按钮。 */
final class BossChatOverlay {
    record Notice(String title,String text) {
        String summary(){return redact(title.isBlank()?text:title+" · "+(text.contains(title)?text.replaceFirst(java.util.regex.Pattern.quote(title),"").strip():text));}
        boolean requiresUserAction(){return (title+" "+text).matches("(?s).*(?:安全验证|异常访问|完成验证|登录已失效|请先登录|重新登录).*");}
    }
    static final String PROBE="""
        el => {
          const doc=el.ownerDocument, win=doc.defaultView, rect=el.getBoundingClientRect();
          if(!el.isConnected || rect.width<=0 || rect.height<=0) return '';
          const x=Math.max(0,Math.min(win.innerWidth-1,rect.x+rect.width/2));
          const y=Math.max(0,Math.min(win.innerHeight-1,rect.y+rect.height/2));
          if(rect.bottom<=0 || rect.top>=win.innerHeight || rect.right<=0 || rect.left>=win.innerWidth) return '';
          const hit=doc.elementFromPoint(x,y);
          if(!hit || hit===el || el.contains(hit)) return '';
          const modal=hit.closest('[data-type="boss-dialog"],.dialog-wrap.active,.greet-boss-dialog,[role="dialog"],[role="alertdialog"],.boss-dialog');
          if(!modal) return '';
          const style=win.getComputedStyle(modal);
          if(!modal.getClientRects().length || style.visibility==='hidden' || style.display==='none') return '';
          const backdrop=hit.matches('.boss-layer__wrapper,.greet-boss-layer,[class*="backdrop"],[class*="mask"],[class*="overlay"]');
          if(modal.contains(el) && !backdrop) return '';
          const title=modal.querySelector('h1,h2,h3,[class*="title"],[class*="header"]');
          return JSON.stringify({title:(title?.innerText||'').trim().slice(0,120),text:(modal.innerText||'').trim().slice(0,1200)});
        }
        """;
    private BossChatOverlay() {}
    static Notice find(Locator target) {
        try {
            if(target.count()==0)return null;
            Object raw=target.evaluate(PROBE,null,new Locator.EvaluateOptions().setTimeout(300));
            if(!(raw instanceof String text) || text.isBlank())return null;
            var value=new JSONObject(text);return new Notice(value.optString("title"),value.optString("text"));
        }catch(PlaywrightException changed) { return null; }
    }
    static String redact(String text) {
        String safe=Normalizer.normalize(text,Normalizer.Form.NFKC)
                .replaceAll("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}","[邮箱]")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)","[电话]").replaceAll("[\\s\\p{Z}]+"," ").strip();
        return safe.length()>180?safe.substring(0,180)+"…":safe;
    }
}
