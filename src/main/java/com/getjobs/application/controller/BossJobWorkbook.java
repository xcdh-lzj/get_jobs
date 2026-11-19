package com.getjobs.application.controller;

import com.getjobs.application.entity.BossJobDataEntity;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class BossJobWorkbook {
    private static final String RECORDS = "岗位记录";
    private static final String DETAILS = "岗位详情";
    private static final int HEADER_ROW = 3;
    private static final int DETAIL_CHUNK_LENGTH = 480;
    private static final int DETAIL_LINE_WIDTH = 86;
    private static final int INK = 0x18322F;
    private static final int TEAL = 0x0F625B;
    private static final int WHITE = 0xFFFFFF;
    private static final int STRIPE = 0xF0F6F5;
    private static final String[] HEADERS = {
            "公司名称", "公司规模", "岗位名称", "薪资", "地点", "学历", "经验",
            "HR职位", "HR活跃", "投递状态", "投递或过滤原因", "岗位详情", "处理时间"
    };
    private static final int[] WIDTHS = {22, 14, 34, 18, 23, 10, 12, 16, 15, 13, 42, 14, 24};

    private BossJobWorkbook() {}

    static void write(List<BossJobDataEntity> jobs, OutputStream output) throws IOException {
        try (var workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            var styles = new Styles(workbook);
            Sheet records = sheet(workbook, RECORDS, "Boss 岗位记录", HEADERS, WIDTHS, 3, styles);
            Sheet details = sheet(workbook, DETAILS, "Boss 岗位详情",
                    new String[]{"项目", "内容"}, new int[]{22, 110}, 0, styles);
            setText(records.getRow(1), 2, "共 " + jobs.size() + " 条岗位", styles.ordinary.body());
            records.setAutoFilter(new CellRangeAddress(HEADER_ROW, HEADER_ROW + jobs.size(), 0, HEADERS.length - 1));

            int detailIndex = HEADER_ROW + 1;
            for (int i = 0; i < jobs.size(); i++) {
                var job = jobs.get(i);
                RowStyles rowStyles = i % 2 == 0 ? styles.ordinary : styles.striped;
                int recordIndex = HEADER_ROW + 1 + i;
                writeRecord(workbook, records, recordIndex, detailIndex, job, styles, rowStyles);
                detailIndex = writeDetails(workbook, details, detailIndex, recordIndex, job, styles, rowStyles);
            }
            workbook.write(output);
        }
    }

    private static Sheet sheet(SXSSFWorkbook workbook, String name, String title, String[] headers,
                               int[] widths, int fixedColumns, Styles styles) {
        Sheet sheet = workbook.createSheet(name);
        sheet.setDisplayGridlines(false);
        sheet.createFreezePane(fixedColumns, HEADER_ROW + 1);
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        Row titleRow = sheet.createRow(1);
        titleRow.setHeightInPoints(25);
        setText(titleRow, 0, title, styles.title);
        Row header = sheet.createRow(HEADER_ROW);
        header.setHeightInPoints(25);
        for (int i = 0; i < headers.length; i++) {
            setText(header, i, headers[i], styles.header);
        }
        return sheet;
    }

    private static void writeRecord(SXSSFWorkbook workbook, Sheet sheet, int index, int detailIndex,
                                    BossJobDataEntity job, Styles styles, RowStyles rowStyles) {
        Row row = sheet.createRow(index);
        row.setHeightInPoints(35);
        String[] values = {
                job.getCompanyName(), job.getCompanyScale(), job.getJobName(), job.getSalary(), job.getLocation(),
                job.getDegree(), job.getExperience(), job.getHrPosition(), job.getHrActiveStatus()
        };
        for (int i = 0; i < values.length; i++) {
            setText(row, i, values[i], i == 2 || i == 3 ? rowStyles.emphasis() : rowStyles.body());
        }
        String status = Objects.toString(job.getDeliveryStatus(), "");
        setText(row, 9, status, styles.statuses.getOrDefault(status, rowStyles.body()));
        setText(row, 10, job.getDeliveryReason(), rowStyles.body());
        link(workbook, setText(row, 11, "查看详情", rowStyles.link()), DETAILS, detailIndex);
        Cell time = row.createCell(12);
        time.setCellStyle(rowStyles.date());
        if (job.getProcessedAt() != null) {
            time.setCellValue(job.getProcessedAt());
        }
    }

    private static int writeDetails(SXSSFWorkbook workbook, Sheet sheet, int index, int recordIndex,
                                    BossJobDataEntity job, Styles styles, RowStyles rowStyles) {
        Row heading = sheet.createRow(index++);
        heading.setHeightInPoints(32);
        link(workbook, setText(heading, 0, "返回岗位记录", styles.detailLink), RECORDS, recordIndex);
        setText(heading, 1, job.getJobName(), styles.detailHeading);
        index = appendField(sheet, index, "公司名称", job.getCompanyName(), rowStyles);
        index = appendField(sheet, index, "职位描述", job.getJobDescription(), rowStyles);
        if (job.getIntroduce() != null && !job.getIntroduce().isBlank()) {
            index = appendField(sheet, index, "公司简介", job.getIntroduce(), rowStyles);
        }
        index = appendField(sheet, index, "投递或过滤原因", job.getDeliveryReason(), rowStyles);
        // 原始 URL 保留为可复制文本。工作簿中的点击跳转仅用于表内导航。
        Row url = sheet.createRow(index);
        url.setHeightInPoints(35);
        setText(url, 0, "岗位链接", rowStyles.emphasis());
        setText(url, 1, job.getJobUrl(), rowStyles.detail());
        return index + 1;
    }

    private static int appendField(Sheet sheet, int index, String label, String value, RowStyles styles) {
        int first = index;
        for (String line : Objects.toString(value, "").split("\\r\\n|\\r|\\n", -1)) {
            // 分拆长段落，保证详情可读，也避开 Excel 的单元格文本长度限制。
            int start = 0;
            do {
                int end = Math.min(line.length(), start + DETAIL_CHUNK_LENGTH);
                if (end < line.length() && Character.isHighSurrogate(line.charAt(end - 1))) {
                    end--;
                }
                String chunk = line.substring(start, end);
                Row row = sheet.createRow(index++);
                int units = chunk.codePoints().map(code -> code == '\t' ? 4 : code < 128 ? 1 : 2).sum();
                int lines = Math.max(1, (units + DETAIL_LINE_WIDTH - 1) / DETAIL_LINE_WIDTH);
                // 英文单词、编号和标点的换行边界可能额外占一行。
                row.setHeightInPoints(6 + 16 * (lines > 1 ? lines + 1 : lines));
                setText(row, 0, row.getRowNum() == first ? label : "", styles.emphasis());
                setText(row, 1, chunk, styles.detail());
                start = end;
            } while (start < line.length());
        }
        return index;
    }

    private static Cell setText(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellStyle(style);
        cell.setCellValue(Objects.toString(value, ""));
        return cell;
    }

    private static void link(SXSSFWorkbook workbook, Cell cell, String sheetName, int rowIndex) {
        var link = workbook.getCreationHelper().createHyperlink(HyperlinkType.DOCUMENT);
        link.setAddress("'" + sheetName + "'!A" + (rowIndex + 1));
        cell.setHyperlink(link);
    }

    private record RowStyles(CellStyle body, CellStyle emphasis, CellStyle link, CellStyle date, CellStyle detail) {}

    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle detailHeading;
        final CellStyle detailLink;
        final RowStyles ordinary;
        final RowStyles striped;
        final Map<String, CellStyle> statuses;

        Styles(SXSSFWorkbook workbook) {
            Font bodyFont = font(workbook, 11, INK, false);
            Font emphasisFont = font(workbook, 11, INK, true);
            XSSFFont linkFont = font(workbook, 11, TEAL, true);
            linkFont.setUnderline(Font.U_SINGLE);
            short dateFormat = workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss");
            title = style(workbook, font(workbook, 15, INK, true), WHITE, HorizontalAlignment.LEFT, false);
            XSSFCellStyle headerStyle = style(workbook, font(workbook, 11, WHITE, true), TEAL, HorizontalAlignment.CENTER, false);
            headerStyle.setBorderRight(BorderStyle.THIN);
            headerStyle.setRightBorderColor(color(WHITE));
            header = headerStyle;
            detailHeading = style(workbook, emphasisFont, 0xDDEFEA, HorizontalAlignment.LEFT, true);
            detailLink = style(workbook, linkFont, 0xDDEFEA, HorizontalAlignment.CENTER, false);
            ordinary = rows(workbook, bodyFont, emphasisFont, linkFont, dateFormat, WHITE);
            striped = rows(workbook, bodyFont, emphasisFont, linkFont, dateFormat, STRIPE);
            statuses = Map.of(
                    "已投递", status(workbook, 0xDCFCE7, 0x166534),
                    "已过滤", status(workbook, 0xFEF3C7, 0x854D0E),
                    "未投递", status(workbook, 0xE2E8F0, 0x334155),
                    "投递失败", status(workbook, 0xFFE4E6, 0x9F1239)
            );
        }

        private static RowStyles rows(SXSSFWorkbook workbook, Font body, Font emphasis, Font link,
                                      short dateFormat, int fill) {
            XSSFCellStyle date = style(workbook, body, fill, HorizontalAlignment.RIGHT, false);
            date.setDataFormat(dateFormat);
            XSSFCellStyle detail = style(workbook, body, fill, HorizontalAlignment.LEFT, true);
            detail.setVerticalAlignment(VerticalAlignment.TOP);
            return new RowStyles(style(workbook, body, fill, HorizontalAlignment.LEFT, true),
                    style(workbook, emphasis, fill, HorizontalAlignment.LEFT, true),
                    style(workbook, link, fill, HorizontalAlignment.CENTER, false), date, detail);
        }

        private static CellStyle status(SXSSFWorkbook workbook, int fill, int ink) {
            return style(workbook, font(workbook, 11, ink, true), fill, HorizontalAlignment.CENTER, false);
        }
    }

    private static XSSFFont font(SXSSFWorkbook workbook, int points, int rgb, boolean bold) {
        XSSFFont font = (XSSFFont) workbook.createFont();
        font.setFontName("Arial");
        font.setFontHeightInPoints((short) points);
        font.setColor(color(rgb));
        font.setBold(bold);
        return font;
    }

    private static XSSFCellStyle style(SXSSFWorkbook workbook, Font font, int fill,
                                       HorizontalAlignment horizontal, boolean wrap) {
        XSSFCellStyle style = (XSSFCellStyle) workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(color(fill));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(horizontal);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(wrap);
        return style;
    }

    private static XSSFColor color(int rgb) {
        return new XSSFColor(new byte[]{(byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb});
    }
}
