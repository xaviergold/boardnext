package com.board.service.chatbot;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ChatExportService {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ─────────────────────────────────────────────────────────────
    // 엑셀 내보내기
    // ─────────────────────────────────────────────────────────────
    public byte[] exportToExcel(String title, List<Map<String, String>> messages)
            throws Exception {

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("대화내용");

            // ── 열 너비 설정 ──────────────────────────────────────
            sheet.setColumnWidth(0, 15 * 256);   // 번호
            sheet.setColumnWidth(1, 15 * 256);   // 구분
            sheet.setColumnWidth(2, 80 * 256);   // 내용
            sheet.setColumnWidth(3, 25 * 256);   // 시간

            // ── 스타일: 제목 ──────────────────────────────────────
            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleFont.setColor(IndexedColors.WHITE.getIndex());
            titleStyle.setFont(titleFont);
            titleStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            titleStyle.setAlignment(HorizontalAlignment.CENTER);
            titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            // ── 스타일: 헤더 ──────────────────────────────────────
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            setBorder(headerStyle);

            // ── 스타일: 사용자 메시지 ─────────────────────────────
            CellStyle userStyle = workbook.createCellStyle();
            userStyle.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
            userStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            userStyle.setWrapText(true);
            userStyle.setVerticalAlignment(VerticalAlignment.TOP);
            setBorder(userStyle);

            // ── 스타일: AI 메시지 ─────────────────────────────────
            CellStyle aiStyle = workbook.createCellStyle();
            aiStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            aiStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            aiStyle.setWrapText(true);
            aiStyle.setVerticalAlignment(VerticalAlignment.TOP);
            setBorder(aiStyle);

            // ── 스타일: 일반 셀 ───────────────────────────────────
            CellStyle normalStyle = workbook.createCellStyle();
            normalStyle.setAlignment(HorizontalAlignment.CENTER);
            normalStyle.setVerticalAlignment(VerticalAlignment.TOP);
            setBorder(normalStyle);

            int rowNum = 0;

            // ── 제목 행 ───────────────────────────────────────────
            Row titleRow = sheet.createRow(rowNum++);
            titleRow.setHeightInPoints(30);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title);
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 3));

            // ── 내보내기 시간 행 ──────────────────────────────────
            Row timeRow = sheet.createRow(rowNum++);
            Cell timeCell = timeRow.createCell(0);
            timeCell.setCellValue("내보내기 시간: " +
                    LocalDateTime.now().format(FORMATTER));
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 0, 3));

            // ── 빈 행 ─────────────────────────────────────────────
            rowNum++;

            // ── 헤더 행 ───────────────────────────────────────────
            Row headerRow = sheet.createRow(rowNum++);
            headerRow.setHeightInPoints(20);
            String[] headers = {"번호", "구분", "내용", "시간"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // ── 데이터 행 ─────────────────────────────────────────
            if (messages != null) {
                int seq = 1;
                for (Map<String, String> msg : messages) {
                    String role = msg.getOrDefault("role", "");
                    String content = msg.getOrDefault("content", "");
                    String timestamp = msg.getOrDefault("timestamp", "");

                    boolean isUser = "user".equals(role);
                    CellStyle contentStyle = isUser ? userStyle : aiStyle;
                    String roleLabel = isUser ? "사용자" : "AI";

                    Row dataRow = sheet.createRow(rowNum++);

                    // 번호
                    Cell seqCell = dataRow.createCell(0);
                    seqCell.setCellValue(seq++);
                    seqCell.setCellStyle(normalStyle);

                    // 구분
                    Cell roleCell = dataRow.createCell(1);
                    roleCell.setCellValue(roleLabel);
                    roleCell.setCellStyle(contentStyle);

                    // 내용
                    Cell contentCell = dataRow.createCell(2);
                    contentCell.setCellValue(content);
                    contentCell.setCellStyle(contentStyle);

                    // 시간
                    Cell timeStampCell = dataRow.createCell(3);
                    timeStampCell.setCellValue(timestamp);
                    timeStampCell.setCellStyle(normalStyle);

                    // 행 높이 자동 조절 (내용 길이 기준)
                    int lines = Math.max(1, content.split("\n").length);
                    dataRow.setHeightInPoints(Math.min(lines * 15f, 300f));
                }
            }

            workbook.write(out);
            log.info("[ChatExport] 엑셀 생성 완료: {}행", rowNum);
            return out.toByteArray();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 워드 내보내기
    // ─────────────────────────────────────────────────────────────
    public byte[] exportToWord(String title, List<Map<String, String>> messages)
            throws Exception {

        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // ── 제목 ──────────────────────────────────────────────
            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText(title);
            titleRun.setBold(true);
            titleRun.setFontSize(18);
            titleRun.setFontFamily("맑은 고딕");
            titleRun.addBreak();

            // ── 내보내기 시간 ─────────────────────────────────────
            XWPFParagraph timePara = doc.createParagraph();
            timePara.setAlignment(ParagraphAlignment.RIGHT);
            XWPFRun timeRun = timePara.createRun();
            timeRun.setText("내보내기: " + LocalDateTime.now().format(FORMATTER));
            timeRun.setFontSize(9);
            timeRun.setColor("888888");
            timeRun.setFontFamily("맑은 고딕");

            // ── 구분선 ────────────────────────────────────────────
            XWPFParagraph divider = doc.createParagraph();
            divider.setBorderBottom(Borders.SINGLE);

            // ── 대화 내용 ─────────────────────────────────────────
            if (messages != null) {
                for (Map<String, String> msg : messages) {
                    String role = msg.getOrDefault("role", "");
                    String content = msg.getOrDefault("content", "");
                    String timestamp = msg.getOrDefault("timestamp", "");

                    boolean isUser = "user".equals(role);

                    // 역할 레이블 단락
                    XWPFParagraph rolePara = doc.createParagraph();
                    rolePara.setAlignment(isUser
                            ? ParagraphAlignment.RIGHT
                            : ParagraphAlignment.LEFT);
                    XWPFRun roleRun = rolePara.createRun();
                    roleRun.setText((isUser ? "👤 사용자" : "🤖 AI") +
                            (timestamp.isEmpty() ? "" : "  " + timestamp));
                    roleRun.setBold(true);
                    roleRun.setFontSize(9);
                    roleRun.setColor(isUser ? "1A6B9A" : "2E7D32");
                    roleRun.setFontFamily("맑은 고딕");

                    // 내용 단락
                    XWPFParagraph contentPara = doc.createParagraph();
                    contentPara.setAlignment(isUser
                            ? ParagraphAlignment.RIGHT
                            : ParagraphAlignment.LEFT);

                    // 들여쓰기
                    contentPara.setIndentationLeft(isUser ? 0 : 200);
                    contentPara.setIndentationRight(isUser ? 200 : 0);

                    // 내용을 줄바꿈 기준으로 분리
                    String[] lines = content.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        XWPFRun contentRun = contentPara.createRun();
                        contentRun.setText(lines[i]);
                        contentRun.setFontSize(10);
                        contentRun.setFontFamily("맑은 고딕");
                        if (i < lines.length - 1) {
                            contentRun.addBreak();
                        }
                    }

                    // 메시지 사이 여백
                    XWPFParagraph spacer = doc.createParagraph();
                    XWPFRun spacerRun = spacer.createRun();
                    spacerRun.setFontSize(6);
                }
            }

            doc.write(out);
            log.info("[ChatExport] 워드 생성 완료: {}개 메시지",
                    messages != null ? messages.size() : 0);
            return out.toByteArray();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 헬퍼: 셀 테두리 설정
    // ─────────────────────────────────────────────────────────────
    private void setBorder(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    // ─────────────────────────────────────────────────────────────
    // 마크다운 표 → 엑셀 변환
    // ─────────────────────────────────────────────────────────────
    public byte[] exportTableToExcel(String title, String markdownTable) throws Exception {

        // 마크다운 표 파싱
        String[] lines = markdownTable.split("\n");
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.replaceAll("[|\\-: ]", "").isEmpty()) continue; // 구분선 제거
            if (!line.startsWith("|")) continue;
            String[] cells = line.substring(1, line.endsWith("|") ? line.length() - 1 : line.length()).split("\\|");
            String[] trimmed = new String[cells.length];
            for (int i = 0; i < cells.length; i++) trimmed[i] = cells[i].trim();
            rows.add(trimmed);
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("데이터");

            // 헤더 스타일
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setWrapText(true);
            setBorder(headerStyle);

            // 데이터 스타일
            CellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setWrapText(true);
            dataStyle.setVerticalAlignment(VerticalAlignment.TOP);
            setBorder(dataStyle);

            // 제목 행
            Row titleRow = sheet.createRow(0);
            titleRow.setHeightInPoints(25);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title);
            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 13);
            titleStyle.setFont(titleFont);
            titleCell.setCellStyle(titleStyle);

            if (!rows.isEmpty()) {
                int maxCols = rows.stream().mapToInt(r -> r.length).max().orElse(1);
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, maxCols - 1));

                int rowNum = 1;
                for (int r = 0; r < rows.size(); r++) {
                    Row row = sheet.createRow(rowNum++);
                    String[] cells = rows.get(r);
                    for (int c = 0; c < cells.length; c++) {
                        Cell cell = row.createCell(c);
                        cell.setCellValue(cells[c]);
                        cell.setCellStyle(r == 0 ? headerStyle : dataStyle);
                    }
                    // 행 높이 자동
                    int maxLines = 1;
                    for (String cell : cells) maxLines = Math.max(maxLines, cell.split("\n").length);
                    row.setHeightInPoints(Math.min(maxLines * 18f, 200f));
                }

                // 열 너비 자동 조절
                for (int c = 0; c < rows.get(0).length; c++) {
                    sheet.autoSizeColumn(c);
                    // 최소 10, 최대 60 문자 너비
                    int width = Math.min(Math.max(sheet.getColumnWidth(c), 10 * 256), 60 * 256);
                    sheet.setColumnWidth(c, width);
                }
            }

            workbook.write(out);
            log.info("[ChatExport] 표 엑셀 생성 완료: {}행", rows.size());
            return out.toByteArray();
        }
    }

}