package com.getjobs.application.controller;

import com.getjobs.application.entity.BossJobDataEntity;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class BossJobWorkbookTest {
    @Test void exportsACompactTableWithTypedTimesAndCompleteDetails() throws IOException {
        var first = new BossJobDataEntity();
        first.setCompanyName("示例公司");
        first.setCompanyScale("100-499人");
        first.setJobName("=1+1");
        first.setDeliveryStatus("已投递");
        first.setDeliveryReason("符合目标职责");
        String longLine = "示".repeat(479) + "😀" + "维护智能体和检索系统。".repeat(220);
        first.setJobDescription("岗位职责\n" + longLine + "\n说明结束");
        first.setIntroduce("模拟公司简介");
        String url = "https://www.zhipin.com/job_detail/fixture.html?source=" + "x".repeat(600);
        first.setJobUrl(url);
        LocalDateTime time = LocalDateTime.of(2026, 11, 19, 10, 24, 31);
        first.setProcessedAt(time);
        var second = new BossJobDataEntity();
        second.setJobName("另一个岗位");
        second.setDeliveryStatus("已过滤");

        try (var workbook = readWorkbook(List.of(first, second))) {
            assertEquals(List.of("岗位记录", "岗位详情"),
                    List.of(workbook.getSheetName(0), workbook.getSheetName(1)));
            var records = workbook.getSheetAt(0);
            var firstRow = records.getRow(4);
            var secondRow = records.getRow(5);
            assertEquals(CellType.STRING, firstRow.getCell(2).getCellType());
            assertEquals("=1+1", firstRow.getCell(2).getStringCellValue());
            assertEquals(CellType.NUMERIC, firstRow.getCell(12).getCellType());
            assertEquals(time, firstRow.getCell(12).getLocalDateTimeCellValue());
            assertEquals("2026-11-19 10:24:31",
                    new DataFormatter(Locale.ROOT).formatCellValue(firstRow.getCell(12)));
            assertEquals(CellType.BLANK, secondRow.getCell(12).getCellType());
            assertEquals(35.0, firstRow.getHeightInPoints());
            assertEquals(firstRow.getHeight(), secondRow.getHeight());
            assertNotEquals(firstRow.getCell(0).getCellStyle().getIndex(), secondRow.getCell(0).getCellStyle().getIndex());
            assertNotEquals(firstRow.getCell(9).getCellStyle().getIndex(), secondRow.getCell(9).getCellStyle().getIndex());
            assertTrue(records.getColumnWidth(12) >= 23 * 256);
            assertEquals(4, records.getPaneInformation().getHorizontalSplitPosition());
            assertEquals(3, records.getPaneInformation().getVerticalSplitPosition());
            assertEquals("A4:M6", records.getCTWorksheet().getAutoFilter().getRef());

            var details = workbook.getSheetAt(1);
            var openDetail = firstRow.getCell(11).getHyperlink();
            assertEquals(HyperlinkType.DOCUMENT, openDetail.getType());
            assertEquals("'岗位详情'!A5", openDetail.getAddress());
            assertEquals("'岗位记录'!A5", details.getRow(4).getCell(0).getHyperlink().getAddress());
            var text = new StringBuilder();
            int nextField = 6;
            do {
                var row = details.getRow(nextField++);
                text.append(row.getCell(1).getStringCellValue());
                assertTrue(row.getHeightInPoints() < 409);
            } while (details.getRow(nextField).getCell(0).getStringCellValue().isEmpty());
            assertEquals("岗位职责" + longLine + "说明结束", text.toString());
            assertEquals("公司简介", details.getRow(nextField).getCell(0).getStringCellValue());
            assertEquals("模拟公司简介", details.getRow(nextField).getCell(1).getStringCellValue());
            var savedLink = details.getRow(nextField + 2).getCell(1);
            assertEquals(url, savedLink.getStringCellValue());
            assertNull(savedLink.getHyperlink());
            assertEquals("'岗位详情'!A" + (nextField + 4), secondRow.getCell(11).getHyperlink().getAddress());
        }
    }

    @Test void exportsHeadersWhenNoJobsMatch() throws IOException {
        try (var workbook = readWorkbook(List.of())) {
            assertEquals("处理时间", workbook.getSheetAt(0).getRow(3).getCell(12).getStringCellValue());
            assertEquals(3, workbook.getSheetAt(0).getLastRowNum());
            assertEquals(3, workbook.getSheetAt(1).getLastRowNum());
        }
    }

    private static XSSFWorkbook readWorkbook(List<BossJobDataEntity> jobs) throws IOException {
        var output = new ByteArrayOutputStream();
        BossJobWorkbook.write(jobs, output);
        return new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()));
    }
}
