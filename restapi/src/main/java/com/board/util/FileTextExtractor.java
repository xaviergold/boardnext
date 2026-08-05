package com.board.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.Loader;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.ByteArrayInputStream;

@Slf4j
public class FileTextExtractor {

    private static final int MAX_CHARS = 20000;

    /**
     * MIME 타입에 따라 적절한 텍스트 추출 메서드를 호출
     */
    public static String extract(byte[] data, String mimeType, String fileName) {
        try {
            String text = switch (mimeType) {
                case "application/pdf"
                        -> extractPdf(data);
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                     "application/msword"
                        -> extractWord(data);
                case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                     "application/vnd.ms-excel"
                        -> extractExcel(data);
                case "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                     "application/vnd.ms-powerpoint"
                        -> extractPpt(data);
                default -> new String(data, "UTF-8"); // txt, csv, md 등
            };

            // 최대 글자수 제한
            if (text.length() > MAX_CHARS) {
                text = text.substring(0, MAX_CHARS) + "\n\n...(이하 생략, 내용이 너무 길어 일부만 분석합니다)";
                log.info("파일 내용 글자수 제한 적용: {} → {}자", fileName, MAX_CHARS);
            }

            return text;

        } catch (Exception e) {
            log.warn("파일 텍스트 추출 실패: name={}, error={}", fileName, e.getMessage());
            return "(파일 내용을 읽지 못했습니다: " + e.getMessage() + ")";
        }
    }

    /**
     * PDF 텍스트 추출 (Apache PDFBox)
     */
    private static String extractPdf(byte[] data) throws Exception {
        try (PDDocument doc = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            log.info("PDF 텍스트 추출 완료: {}페이지, {}자", doc.getNumberOfPages(), text.length());
            return text;
        }
    }

    /**
     * Word (.docx / .doc) 텍스트 추출 (Apache POI)
     */
    private static String extractWord(byte[] data) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                }
            }
            log.info("Word 텍스트 추출 완료: {}자", sb.length());
            return sb.toString();
        }
    }

    /**
     * Excel (.xlsx / .xls) 텍스트 추출 (Apache POI)
     * WorkbookFactory 사용으로 .xls(OLE2)와 .xlsx(OOXML) 모두 처리
     */
    private static String extractExcel(byte[] data) throws Exception {
        // WorkbookFactory가 .xls / .xlsx 자동 감지
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                var sheet = workbook.getSheetAt(i);
                sb.append("[시트: ").append(sheet.getSheetName()).append("]\n");
                for (var row : sheet) {
                    StringBuilder rowSb = new StringBuilder();
                    for (var cell : row) {
                        String cellValue = "";
                        switch (cell.getCellType()) {
                            case STRING  -> cellValue = cell.getStringCellValue();
                            case NUMERIC -> cellValue = String.valueOf((long) cell.getNumericCellValue());
                            case BOOLEAN -> cellValue = String.valueOf(cell.getBooleanCellValue());
                            default      -> cellValue = "";
                        }
                        // 연속 공백 제거 후 | 구분자로 연결
                        cellValue = cellValue.trim().replaceAll("\\s+", " ");
                        if (!cellValue.isEmpty()) {
                            if (rowSb.length() > 0) rowSb.append(" | ");
                            rowSb.append(cellValue);
                        }
                    }
                    String rowText = rowSb.toString().trim();
                    // 의미있는 내용(한글 또는 영숫자)이 있는 행만 저장
                    if (!rowText.isEmpty() && (
                            rowText.chars().anyMatch(c -> (c >= 0xAC00 && c <= 0xD7A3)) ||
                            rowText.chars().anyMatch(Character::isLetterOrDigit))) {
                        sb.append(rowText).append("\n");
                    }
                }
                sb.append("\n");
            }
            log.info("Excel 텍스트 추출 완료: {}시트, {}자", workbook.getNumberOfSheets(), sb.length());
            return sb.toString();
        }
    }

    /**
     * PowerPoint (.pptx) 텍스트 추출 (Apache POI)
     */
    private static String extractPpt(byte[] data) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();
            int slideNum = 1;
            for (XSLFSlide slide : ppt.getSlides()) {
                sb.append("[슬라이드 ").append(slideNum++).append("]\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            sb.append(text).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }
            log.info("PPT 텍스트 추출 완료: {}슬라이드, {}자", ppt.getSlides().size(), sb.length());
            return sb.toString();
        }
    }
}