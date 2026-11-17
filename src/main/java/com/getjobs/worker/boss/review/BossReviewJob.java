package com.getjobs.worker.boss.review;

import com.getjobs.worker.boss.BossSalary;
import java.net.URI;
import java.util.Locale;

public record BossReviewJob(String id, String title, String company, String city, String salary,
                            String experience, String degree, String recruiterTitle, String activeStatus,
                            String description, String detailUrl, String keyword) {
    public BossReviewJob {
        id = clean(id); title = clean(title); company = clean(company); city = clean(city);
        salary = BossSalary.normalize(salary); experience = clean(experience); degree = clean(degree);
        recruiterTitle = clean(recruiterTitle); activeStatus = clean(activeStatus);
        description = clean(description); detailUrl = safeDetailUrl(detailUrl); keyword = clean(keyword);
    }
    public static String clean(String value) { return value == null ? "" : value.strip(); }
    public static String safeDetailUrl(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI url = URI.create(value.startsWith("/") ? "https://www.zhipin.com" + value : value);
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getUserInfo() != null
                    || !"www.zhipin.com".equalsIgnoreCase(url.getHost()) || url.getPort() != -1
                    || !url.getPath().startsWith("/job_detail/") || url.getPath().contains("..")) return "";
            return url.toString();
        } catch (IllegalArgumentException ignored) { return ""; }
    }
    public String key() {
        if (!detailUrl.isBlank()) return URI.create(detailUrl).getPath();
        if (!id.isBlank()) return id;
        return (title + "|" + company + "|" + city + "|" + salary).toLowerCase(Locale.ROOT);
    }
    public BossReviewJob withDescription(String text) {
        return new BossReviewJob(id,title,company,city,salary,experience,degree,recruiterTitle,activeStatus,text,detailUrl,keyword);
    }
    public BossReviewJob withSalary(String text) {
        return new BossReviewJob(id,title,company,city,text,experience,degree,recruiterTitle,activeStatus,description,detailUrl,keyword);
    }
    public BossReviewJob withActivity(String text) {
        return new BossReviewJob(id,title,company,city,salary,experience,degree,recruiterTitle,text,description,detailUrl,keyword);
    }
}
