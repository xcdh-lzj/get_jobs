package com.getjobs.application.controller;

import com.getjobs.application.entity.BossJobDataEntity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BossJobCsvTest {
    @Test void exportsColumnsInOrderWithUnicodeEscapedMultilineTextAndSecondPrecision() {
        var job = new BossJobDataEntity();
        job.setCompanyName("示例公司");
        job.setCompanyScale("100-499人");
        job.setJobName("=1+1");
        job.setSalary("18-25K");
        job.setLocation("广州,天河");
        job.setDegree("本科");
        job.setExperience("1-3年");
        job.setHrPosition("招聘经理");
        job.setHrActiveStatus("今日活跃");
        job.setDeliveryStatus("已投递");
        job.setDeliveryReason("已完成招呼语发送流程");
        job.setJobDescription("第一行\n负责\"Agent\"开发");
        job.setJobUrl("https://www.zhipin.com/job_detail/fixture.html");
        job.setDeliveredAt(LocalDateTime.of(2026, 11, 18, 9, 24, 28));
        job.setProcessedAt(LocalDateTime.of(2026, 11, 18, 9, 24, 30));
        var csv = new String(BossJobCsv.encode(List.of(job)), StandardCharsets.UTF_8);
        String header = "\uFEFF\"公司名称\",\"公司规模\",\"岗位名称\",\"薪资\",\"地点\",\"学历\",\"经验\","
                + "\"HR职位\",\"HR活跃\",\"投递状态\",\"投递或过滤原因\",\"职位描述\",\"岗位链接\",\"处理时间\"\r\n";
        String row = "\"示例公司\",\"100-499人\",\"'=1+1\",\"18-25K\",\"广州,天河\",\"本科\",\"1-3年\","
                + "\"招聘经理\",\"今日活跃\",\"已投递\",\"已完成招呼语发送流程\",\"第一行\n负责\"\"Agent\"\"开发\","
                + "\"https://www.zhipin.com/job_detail/fixture.html\",\"2026-11-18 09:24:30\"\r\n";
        assertEquals(header + row, csv);
    }
}
