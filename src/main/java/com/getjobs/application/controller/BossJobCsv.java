package com.getjobs.application.controller;

import com.getjobs.application.entity.BossJobDataEntity;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

final class BossJobCsv {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private BossJobCsv() {}

    static byte[] encode(List<BossJobDataEntity> jobs) {
        var csv = new StringBuilder("\uFEFF");
        appendRow(csv, "公司名称", "公司规模", "岗位名称", "薪资", "地点", "学历", "经验",
                "HR职位", "HR活跃", "投递状态", "投递或过滤原因", "职位描述", "岗位链接", "投递时间");
        for (var job : jobs) {
            var deliveredAt = job.getDeliveredAt();
            appendRow(csv, job.getCompanyName(), job.getCompanyScale(), job.getJobName(), job.getSalary(),
                    job.getLocation(), job.getDegree(), job.getExperience(), job.getHrPosition(), job.getHrActiveStatus(),
                    job.getDeliveryStatus(), job.getDeliveryReason(), job.getJobDescription(), job.getJobUrl(),
                    deliveredAt == null ? "" : TIME.format(deliveredAt));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendRow(StringBuilder csv, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) csv.append(',');
            String value = fields[i] == null ? "" : fields[i];
            String leading = value.stripLeading();
            csv.append('"');
            // 避免表格软件把采集到的文本当作公式求值。
            if (value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")
                    || (!leading.isEmpty() && "=+-@＝＋－＠".indexOf(leading.charAt(0)) >= 0)) {
                csv.append('\'');
            }
            csv.append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
}
