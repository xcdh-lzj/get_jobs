package com.getjobs.worker.boss;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** BOSS 薪资的本地解码与月薪解析；未知编码、计薪周期和不完整数字不作猜测。 */
public final class BossSalary {
    private static final Pattern MONTHLY = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)(?:-(\\d+(?:\\.\\d+)?))?(K|千|万|元)(?:/月|每月)?(?:[·.](\\d{1,2})薪)?$",
            Pattern.CASE_INSENSITIVE);
    public record Monthly(double minK, double maxK, int months) {}
    private BossSalary() {}

    public static String normalize(String raw) {
        if (raw == null) return "";
        StringBuilder decoded = new StringBuilder();
        for (char c : raw.toCharArray()) {
            // 当前数据与同一岗位的明文历史记录核对一致；同时兼容项目原有的旧字体段。
            if (c >= '\uE031' && c <= '\uE03A') c = (char) ('0' + c - '\uE031');
            else if (c >= '\uE8F0' && c <= '\uE8F9') c = (char) ('0' + c - '\uE8F0');
            decoded.append(c);
        }
        return Normalizer.normalize(decoded, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}\\u200B\\uFEFF]+", "")
                .replaceAll("[–—−﹣~～至]", "-")
                .replace('•', '·');
    }

    public static Monthly parse(String raw) {
        String text = normalize(raw).replaceFirst("^月薪", "");
        var match = MONTHLY.matcher(text);
        if (!match.matches()) return null;
        try {
            double unit = switch (match.group(3).toUpperCase()) {
                case "万" -> 10;
                case "元" -> 0.001;
                default -> 1;
            };
            double min = Double.parseDouble(match.group(1)) * unit;
            double max = match.group(2) == null ? min : Double.parseDouble(match.group(2)) * unit;
            int months = match.group(4) == null ? 12 : Integer.parseInt(match.group(4));
            if (!Double.isFinite(min) || !Double.isFinite(max) || min <= 0 || max < min
                    || max > 1_000_000 || months < 1 || months > 36) return null;
            return new Monthly(min, max, months);
        } catch (NumberFormatException ignored) { return null; }
    }
}
