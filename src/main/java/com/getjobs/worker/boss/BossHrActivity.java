package com.getjobs.worker.boss;

import java.text.Normalizer;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 本地识别平台的活跃范围；不把“本周”误当作“刚刚”，不猜测未知文本。 */
public final class BossHrActivity {
    public enum Decision { ACCEPT, REJECT, UNKNOWN }
    public record Assessment(Decision decision,String reason) {}
    private static final Set<String> NOW=Set.of("在线","当前在线","正在活跃","刚刚活跃","刚刚在线");
    private static final Map<String,Integer> WINDOWS=Map.ofEntries(
            Map.entry("今日活跃",1),Map.entry("今天活跃",1),Map.entry("今日在线",1),Map.entry("今天在线",1),
            Map.entry("昨日活跃",2),Map.entry("昨天活跃",2),Map.entry("本周活跃",7),Map.entry("本周在线",7),
            Map.entry("本月活跃",31),Map.entry("本月在线",31),Map.entry("半年前活跃",183),Map.entry("半年内活跃",183));
    private static final Pattern DURATION=Pattern.compile("(?:最近|近)?([0-9]{1,6}|一|两|二|三|四|五|六|七|八|九|十)(分钟|小时|天|日|周|星期|个月|月|年)(?:内|前)(?:活跃|在线)");
    private BossHrActivity() {}

    public static Assessment assess(String raw,int maxDays) {
        new BossHrActivityPolicy(maxDays).validated();
        String value=Normalizer.normalize(raw==null?"":raw,Normalizer.Form.NFKC).replaceAll("\\s+","");
        if(value.isBlank())return new Assessment(Decision.UNKNOWN,"未读取到 HR 最近活跃时间，已自动跳过，不影响后续岗位");
        if(NOW.contains(value))return accepted(raw,maxDays);
        Integer upper=WINDOWS.get(value);
        var match=DURATION.matcher(value);
        if(upper==null && match.matches()) {
            int n=number(match.group(1));
            upper=switch(match.group(2)) {
                case "分钟" -> (int)Math.ceil(n/1440.0);
                case "小时" -> (int)Math.ceil(n/24.0);
                case "天","日" -> n;
                case "周","星期" -> n*7;
                case "个月","月" -> n*31;
                case "年" -> n*366;
                default -> null;
            };
        }
        if(upper!=null)return upper<=maxDays?accepted(raw,maxDays):new Assessment(Decision.REJECT,
                "HR 活跃范围为“"+raw+"”，无法确认近 "+maxDays+" 天内活跃，已跳过");
        if(Set.of("长期未活跃","很久未活跃","很久没活跃").contains(value))return new Assessment(Decision.REJECT,"HR 长期未活跃，已跳过");
        return new Assessment(Decision.UNKNOWN,"无法识别 HR 活跃状态“"+raw+"”，已自动跳过，不影响后续岗位");
    }
    private static Assessment accepted(String raw,int days){return new Assessment(Decision.ACCEPT,"HR 活跃状态“"+raw+"”符合近 "+days+" 天要求");}
    private static int number(String value){
        return switch(value){case "一"->1;case "两","二"->2;case "三"->3;case "四"->4;case "五"->5;case "六"->6;case "七"->7;case "八"->8;case "九"->9;case "十"->10;default->Integer.parseInt(value);};
    }
}
