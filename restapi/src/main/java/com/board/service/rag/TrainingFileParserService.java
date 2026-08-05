package com.board.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 훈련 관련 파일 파싱 서비스
 *
 * 파일 종류 자동 감지:
 *   출석부 (xls)      : "훈련과정명" + "실시훈련생" 키워드
 *                       → training_course + training_student 저장
 *
 *   참여자명단 (xlsx)  : "훈련참여자 명단" + "소속" 컬럼
 *                       → training_student.company, phone UPDATE
 *
 *   일반 문서          : 위 두 가지 아닌 경우
 *                       → vector_store (RagIngestionService에서 처리)
 */
@Slf4j
@Service
public class TrainingFileParserService {

    // ── pgvector DB JdbcTemplate (PgVectorDataSourceConfig에서 주입) ──
    private final JdbcTemplate pgVectorJdbcTemplate;

    // @Qualifier + @RequiredArgsConstructor 충돌 방지 → 생성자 직접 주입
    public TrainingFileParserService(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate) {
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
    }

    // ── 파일 종류 감지 패턴 ──────────────────────────────────────────
    private static final Pattern ATTENDANCE_PATTERN =
            Pattern.compile(".*(훈련과정명|실시훈련생).*", Pattern.DOTALL);
    private static final Pattern PARTICIPANT_PATTERN =
            Pattern.compile(".*(훈련참여자\\s*명단|참여자\\s*명단).*", Pattern.DOTALL);

    // ── 날짜 파싱 패턴 ───────────────────────────────────────────────
    // "2026-03-06 ~ 2026-04-02" 또는 "2026. 03. 06. ~ 2026. 04. 02."
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{4})[.\\-]\\s*(\\d{2})[.\\-]\\s*(\\d{2})\\.?\\s*~\\s*(\\d{4})[.\\-]\\s*(\\d{2})[.\\-]\\s*(\\d{2})");

    /**
     * 파일 종류 감지
     */
    public enum FileType {
        ATTENDANCE,    // 출석부
        PARTICIPANT,   // 참여자명단
        GENERAL        // 일반 문서
    }

    public FileType detectFileType(File file) {
        String name = file.getName().toLowerCase();
        if (!name.endsWith(".xls") && !name.endsWith(".xlsx")) {
            return FileType.GENERAL;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            Workbook wb = name.endsWith(".xlsx")
                    ? new XSSFWorkbook(fis)
                    : new HSSFWorkbook(fis);
            Sheet sheet = wb.getSheetAt(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(sheet.getLastRowNum(), 20); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                for (Cell cell : row) {
                    sb.append(getCellString(cell)).append(" ");
                }
            }
            String preview = sb.toString();
            wb.close();

            if (PARTICIPANT_PATTERN.matcher(preview).find()) return FileType.PARTICIPANT;
            if (ATTENDANCE_PATTERN.matcher(preview).find())  return FileType.ATTENDANCE;
            return FileType.GENERAL;

        } catch (Exception e) {
            log.warn("[TrainingParser] 파일 감지 실패: {}", e.getMessage());
            return FileType.GENERAL;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 출석부 파싱 → training_course + training_student 저장
    // ══════════════════════════════════════════════════════════════════
    @Transactional
    public int parseAttendanceFile(File file, String originalFileName) {
        log.info("[TrainingParser] 출석부 파싱 시작: {}", originalFileName);
        try (FileInputStream fis = new FileInputStream(file)) {
            Workbook wb = originalFileName.toLowerCase().endsWith(".xlsx")
                    ? new XSSFWorkbook(fis)
                    : new HSSFWorkbook(fis);
            Sheet sheet = wb.getSheetAt(0);

            // ── 헤더 정보 파싱 ──────────────────────────────────────
            String courseName   = "";
            String periodStr    = "";
            int capacity        = 0;
            int totalHours      = 0;
            int totalDays       = 0;
            int studentCount    = 0;

            for (int i = 0; i <= Math.min(sheet.getLastRowNum(), 30); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String first = getCellString(row.getCell(0));
                String val   = getRowConcatValue(row, 1, 10);

                if (first.contains("훈련과정명")) courseName   = val.replaceAll("\\(ABB[^)]+\\)", "").trim();
                if (first.contains("훈련기간"))   periodStr    = val;
                if (first.contains("정원"))       capacity     = parseIntFromStr(val);
                if (first.contains("훈련시간"))   {
                    totalHours = parseIntFromStr(val.replaceAll("시간.*", ""));
                    totalDays  = parseIntFromStr(val.replaceAll(".*/(\\d+)일.*", "$1"));
                }
                if (first.contains("실시훈련생")) studentCount = parseIntFromStr(val);
            }

            // 날짜 파싱
            LocalDate startDate = null, endDate = null;
            Matcher m = DATE_PATTERN.matcher(periodStr);
            if (m.find()) {
                startDate = LocalDate.of(
                        Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)));
                endDate = LocalDate.of(
                        Integer.parseInt(m.group(4)),
                        Integer.parseInt(m.group(5)),
                        Integer.parseInt(m.group(6)));
            }

            log.info("[TrainingParser] 과정명: {}, 기간: {} ~ {}, 정원: {}, 실시훈련생: {}",
                    courseName, startDate, endDate, capacity, studentCount);

            // ── 기존 과정 확인 (중복 방지) ──────────────────────────
            String checkSql = "SELECT id FROM training_course WHERE course_name = ? AND start_date = ?";
            List<Integer> existing = pgVectorJdbcTemplate.queryForList(
                    checkSql, Integer.class, courseName, startDate);

            int courseId;
            if (!existing.isEmpty()) {
                courseId = existing.get(0);
                log.info("[TrainingParser] 기존 과정 사용: courseId={}", courseId);
                // 기존 훈련생 데이터 삭제 후 재저장
                pgVectorJdbcTemplate.update(
                        "DELETE FROM training_student WHERE course_id = ?", courseId);
            } else {
                // 신규 과정 저장
                String insertCourse = """
                        INSERT INTO training_course
                        (course_name, start_date, end_date, capacity, total_hours, total_days, student_count, source_file)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """;
                courseId = pgVectorJdbcTemplate.queryForObject(insertCourse, Integer.class,
                        courseName, startDate, endDate, capacity, totalHours, totalDays,
                        studentCount, originalFileName);
                log.info("[TrainingParser] 신규 과정 저장: courseId={}", courseId);
            }

            // ── 훈련생 데이터 파싱 ──────────────────────────────────
            List<Object[]> students = new ArrayList<>();
            Pattern studentPattern = Pattern.compile(
                    "^(\\d+\\.?\\d*)$");  // 연번 패턴

            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                // 연번, 이름, 상태, 훈련일수, 출석일수, 결석일수, 출석률(일), 출석률(분) 파싱
                String seqStr    = getCellString(row.getCell(1));
                String name      = getCellString(row.getCell(3));
                String status    = "";
                double trainDays = 0, attendDays = 0, absentDays = 0;
                double rateDay   = 0, rateMin    = 0;

                // 연번 확인
                if (!seqStr.matches("\\d+\\.?\\d*") || name.isEmpty()) continue;
                if (name.equals("성명") || name.length() > 5) continue;

                // 상태 및 수치 파싱
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    String v = getCellString(row.getCell(c));
                    if (v.equals("정상수료") || v.equals("미수료")) status = v;
                }
                if (status.isEmpty()) continue;

                // 수치 컬럼 파싱 (훈련일수~출석률(분))
                List<Double> nums = new ArrayList<>();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    if (cell == null) continue;
                    if (cell.getCellType() == CellType.NUMERIC) {
                        double v = cell.getNumericCellValue();
                        if (v > 0 && v <= 100) nums.add(v);
                    } else {
                        String sv = getCellString(cell);
                        if (sv.matches("\\d+\\.\\d+") && !sv.equals(seqStr)) {
                            try { nums.add(Double.parseDouble(sv)); } catch (Exception ignored) {}
                        }
                    }
                }
                // 마지막 5개 숫자: 훈련일수, 출석일수, 결석일수, 출석률(일), 출석률(분)
                if (nums.size() >= 5) {
                    int s = nums.size();
                    trainDays  = nums.get(s-5);
                    attendDays = nums.get(s-4);
                    absentDays = nums.get(s-3);
                    rateDay    = nums.get(s-2);
                    rateMin    = nums.get(s-1);
                }

                int seqNo = (int) Double.parseDouble(seqStr);
                students.add(new Object[]{
                        courseId, seqNo, name, status,
                        (int) trainDays, (int) attendDays, (int) absentDays,
                        rateDay, rateMin
                });
                log.debug("[TrainingParser] 훈련생: {} {} {} {}일 {}%",
                        seqNo, name, status, (int)attendDays, rateDay);
            }

            // ── 훈련생 배치 저장 ─────────────────────────────────────
            String insertStudent = """
                    INSERT INTO training_student
                    (course_id, seq_no, student_name, status,
                     train_days, attend_days, absent_days, attend_rate_day, attend_rate_min)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            pgVectorJdbcTemplate.batchUpdate(insertStudent, students);

            wb.close();
            log.info("[TrainingParser] 출석부 파싱 완료: {}명 저장", students.size());
            return students.size();

        } catch (Exception e) {
            log.error("[TrainingParser] 출석부 파싱 오류: {}", e.getMessage(), e);
            throw new RuntimeException("출석부 파싱 실패: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 참여자명단 파싱 → training_student.company, phone UPDATE
    // ══════════════════════════════════════════════════════════════════
    @Transactional
    public int parseParticipantFile(File file, String originalFileName) {
        log.info("[TrainingParser] 참여자명단 파싱 시작: {}", originalFileName);
        try (FileInputStream fis = new FileInputStream(file)) {
            Workbook wb = originalFileName.toLowerCase().endsWith(".xlsx")
                    ? new XSSFWorkbook(fis)
                    : new HSSFWorkbook(fis);
            Sheet sheet = wb.getSheetAt(0);

            // 과정명 파싱
            String courseName = "";
            String periodStr  = "";
            for (int i = 0; i <= Math.min(sheet.getLastRowNum(), 10); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String first = getCellString(row.getCell(0));
                String val   = getCellString(row.getCell(1));
                if (first.contains("과정명")) courseName = val;
                if (first.contains("훈련기간")) periodStr = val;
            }
            log.info("[TrainingParser] 참여자명단 과정명: {}", courseName);

            // 날짜 파싱으로 course_id 찾기
            Matcher m = DATE_PATTERN.matcher(periodStr);
            LocalDate startDate = null;
            if (m.find()) {
                startDate = LocalDate.of(
                        Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)));
            }

            // course_id 조회
            Integer courseId = null;
            if (startDate != null) {
                String findCourseSql = "SELECT id FROM training_course WHERE start_date = ? LIMIT 1";
                List<Integer> ids = pgVectorJdbcTemplate.queryForList(
                        findCourseSql, Integer.class, startDate);
                if (!ids.isEmpty()) courseId = ids.get(0);
            }
            if (courseId == null && !courseName.isEmpty()) {
                String findCourseSql = "SELECT id FROM training_course WHERE course_name LIKE ? LIMIT 1";
                List<Integer> ids = pgVectorJdbcTemplate.queryForList(
                        findCourseSql, Integer.class, "%" + courseName.substring(0, Math.min(20, courseName.length())) + "%");
                if (!ids.isEmpty()) courseId = ids.get(0);
            }

            // ── 참여자 데이터 파싱 ──────────────────────────────────
            boolean dataSection = false;
            int updateCount = 0;

            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String first = getCellString(row.getCell(0));

                // 헤더 행 감지
                if (first.equals("소속")) { dataSection = true; continue; }
                if (!dataSection) continue;

                String company = getCellString(row.getCell(0));
                String name    = getCellString(row.getCell(1));
                // 주민번호는 저장하지 않음 (개인정보)
                String phone   = getCellString(row.getCell(3));

                if (name.isEmpty() || company.isEmpty()) continue;

                log.debug("[TrainingParser] 참여자: {} {} {}", company, name, phone);

                // training_student UPDATE
                String updateSql;
                int updated;
                if (courseId != null) {
                    updateSql = "UPDATE training_student SET company=?, phone=? WHERE student_name=? AND course_id=?";
                    updated = pgVectorJdbcTemplate.update(updateSql, company, phone, name, courseId);
                } else {
                    // 과정 매칭 안 되면 이름만으로 UPDATE (전체)
                    updateSql = "UPDATE training_student SET company=?, phone=? WHERE student_name=?";
                    updated = pgVectorJdbcTemplate.update(updateSql, company, phone, name);
                }

                if (updated == 0) {
                    // 출석부에 없는 훈련생은 INSERT 안 함
                    // (출석부가 먼저 등록된 후 참여자명단으로 UPDATE 해야 함)
                    log.warn("[TrainingParser] 매칭되는 훈련생 없음 (출석부 미등록): {}", name);
                }
                updateCount++;
            }

            wb.close();
            log.info("[TrainingParser] 참여자명단 파싱 완료: {}명 처리", updateCount);
            return updateCount;

        } catch (Exception e) {
            log.error("[TrainingParser] 참여자명단 파싱 오류: {}", e.getMessage(), e);
            throw new RuntimeException("참여자명단 파싱 실패: " + e.getMessage());
        }
    }

    // ── 헬퍼 메서드 ─────────────────────────────────────────────────
    private String getCellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                yield v == (long) v ? String.valueOf((long) v) : String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default      -> "";
        };
    }

    private String getRowConcatValue(Row row, int startCol, int endCol) {
        if (row == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int c = startCol; c <= endCol && c < row.getLastCellNum(); c++) {
            String v = getCellString(row.getCell(c));
            if (!v.isEmpty()) sb.append(v).append(" ");
        }
        return sb.toString().trim();
    }

    private int parseIntFromStr(String s) {
        if (s == null) return 0;
        Matcher m = Pattern.compile("(\\d+)").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}