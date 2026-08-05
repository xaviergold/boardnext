package com.board.service.chatbot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 훈련생 DB 조회 Tool
 *
 * ATTENDANCE_DB 카테고리 질문 시 호출됨
 * pgvector DB의 training_course, training_student 테이블에서 정확한 수치 조회
 */
@Slf4j
@Service
public class TrainingQueryService {

    private final JdbcTemplate pgVectorJdbcTemplate;

    // @Qualifier + @RequiredArgsConstructor 충돌 방지 → 생성자 직접 주입
    public TrainingQueryService(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate) {
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
    }

    private static final Pattern ALLOWED_PATTERN =
            Pattern.compile("^\\s*(SELECT|WITH)\\s+.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BLOCKED_PATTERN =
            Pattern.compile(".*(DROP|DELETE|UPDATE|INSERT|ALTER|TRUNCATE|CREATE|GRANT|REVOKE).*",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Tool 1: 훈련 DB 스키마 조회
     * AI가 SQL 생성 전에 먼저 스키마를 확인한다.
     */
    @Tool(description = """
            훈련생 PostgreSQL DB의 테이블 스키마 정보를 반환한다.
            수료생 수, 명단, 출석률, 과정 목록 등 훈련 관련 수치 질문 시
            반드시 이 도구로 스키마를 먼저 확인한 후 SQL을 생성할 것.
            """)
    public String getTrainingSchema() {
        log.info("[TrainingQuery] 스키마 조회");
        return """
                [훈련생 PostgreSQL DB 스키마]
                
                1. training_course (훈련과정 테이블)
                   - id           SERIAL PK
                   - course_name  VARCHAR(300)  - 훈련과정명
                   - start_date   DATE          - 훈련시작일
                   - end_date     DATE          - 훈련종료일
                   - capacity     INT           - 정원
                   - total_hours  INT           - 훈련시간
                   - total_days   INT           - 훈련일수
                   - student_count INT          - 실시훈련생 수
                   - source_file  VARCHAR(200)  - 원본 파일명
                
                2. training_student (훈련생 테이블)
                   - id              SERIAL PK
                   - course_id       INT FK → training_course.id
                   - seq_no          INT           - 연번
                   - student_name    VARCHAR(50)   - 성명
                   - company         VARCHAR(100)  - 소속 회사
                   - phone           VARCHAR(20)   - 전화번호
                   - status          VARCHAR(20)   - 정상수료 / 미수료
                   - train_days      INT           - 훈련일수
                   - attend_days     INT           - 출석일수
                   - absent_days     INT           - 결석일수
                   - attend_rate_day NUMERIC(5,2)  - 출석률(일) %
                   - attend_rate_min NUMERIC(5,2)  - 출석률(분) %
                
                [SQL 생성 규칙]
                - PostgreSQL 문법 사용 (Oracle 아님)
                - SELECT 문만 허용
                - 세미콜론(;) 금지
                - 바인드 변수 금지 → 값을 리터럴로 직접 포함
                - 결과 많으면 LIMIT 50 적용
                - 수료생: WHERE status = '정상수료'
                - 미수료: WHERE status = '미수료'
                
                [GROUP BY 규칙 - 중요]
                - 훈련생 집계 시 GROUP BY는 반드시 student_name만 사용하세요
                - company, phone을 GROUP BY에 포함하면 같은 사람이 여러 행으로 나뉩니다
                - company, phone은 MAX() 또는 MIN()으로 가져오세요
                  예: MAX(s.company) AS company, MAX(s.phone) AS phone
                
                [답변 규칙 - 반드시 준수]
                - executeTrainingSql()이 반환한 숫자와 데이터를 절대 임의로 변경하지 마세요
                - SQL 조회 결과의 count, 건수를 그대로 사용하세요
                - 계산하거나 추측하지 말고 SQL 결과를 100% 그대로 표시하세요
                - "조회 결과 (N건)" 에서 N이 3이면 반드시 3으로 표시하세요
                - 결과를 요약하거나 재해석하지 마세요
                
                [중요 - 과정명 검색 시 주의]
                실제 과정명 예시:
                  장기유급휴가훈련_Nuxt 3 기반 실무 Vue 프론트엔드 개발자 양성 과정
                  (장기유급)React&Next.js 기반 웹 서비스 구현을 위한 개발자 향상 훈련
                  (장기유급)Docker & Kubernetes 기반 웹 서비스 고도화를 위한 개발자 향상 훈련
                  장기유급휴가훈련_Vue 기반 서비스 자동화 빌드 및 배포를 위한 개발자 향상 훈련
                과정명 검색 시 LIKE 조건 없이 전체 조회 후 필터링하거나 일부 키워드 사용

                [수료생 수 조회 - 반드시 이 방식 사용]
                -- 전체 수료생 수
                SELECT COUNT(*) AS total
                FROM training_student
                WHERE status = '정상수료'

                -- 과정별 수료생 수
                SELECT tc.course_name, COUNT(*) AS completed
                FROM training_student ts
                JOIN training_course tc ON ts.course_id = tc.id
                WHERE ts.status = '정상수료'
                GROUP BY tc.course_name
                ORDER BY tc.start_date

                [현재 등록된 과정 목록 조회 예시]
                SELECT id, course_name, start_date, end_date, student_count
                FROM training_course
                ORDER BY start_date
                """;
    }

    /**
     * Tool 2: 전체 수료생 수 조회
     */
    @Tool(description = "전체 장기훈련 수료생 수를 조회한다. '전체 수료생', '총 수료생 수' 등의 질문에 사용")
    public String getTotalCompletedCount() {
        log.info("[TrainingQuery] 전체 수료생 수 조회");
        String sql = "SELECT COUNT(*) AS 전체_수료생수 FROM training_student WHERE status = '정상수료'";
        return executeTrainingSql(sql);
    }

    /**
     * Tool 3: 전체 참가 훈련생 수 조회
     */
    @Tool(description = "전체 장기훈련 참가 훈련생 수를 조회한다. '전체 훈련생', '총 참가자 수' 등의 질문에 사용")
    public String getTotalStudentCount() {
        log.info("[TrainingQuery] 전체 훈련생 수 조회");
        String sql = "SELECT COUNT(*) AS 전체_훈련생수, " +
                     "SUM(CASE WHEN status = '정상수료' THEN 1 ELSE 0 END) AS 수료생수, " +
                     "SUM(CASE WHEN status = '미수료' THEN 1 ELSE 0 END) AS 미수료생수 " +
                     "FROM training_student";
        return executeTrainingSql(sql);
    }

    /**
     * Tool 4: 과정별 수료생/미수료생 수 조회
     */
    @Tool(description = "과정별 수료생 수와 미수료생 수를 조회한다. '과정별 수료', '과정별 현황' 등의 질문에 사용")
    public String getCompletedCountByCourse() {
        log.info("[TrainingQuery] 과정별 수료생 수 조회");
        String sql = "SELECT tc.course_name AS 과정명, tc.start_date AS 시작일, " +
                     "COUNT(*) AS 전체_훈련생수, " +
                     "SUM(CASE WHEN ts.status = '정상수료' THEN 1 ELSE 0 END) AS 수료생수, " +
                     "SUM(CASE WHEN ts.status = '미수료' THEN 1 ELSE 0 END) AS 미수료생수 " +
                     "FROM training_student ts " +
                     "JOIN training_course tc ON ts.course_id = tc.id " +
                     "GROUP BY tc.id, tc.course_name, tc.start_date " +
                     "ORDER BY tc.start_date";
        return executeTrainingSql(sql);
    }

    /**
     * Tool 5: 과정별 회사별 참여/수료 훈련생 수 조회
     */
    @Tool(description = "과정별 회사별 참여 훈련생 수와 수료 훈련생 수를 조회한다. '회사별', '기업별' 등의 질문에 사용")
    public String getCountByCourseAndCompany() {
        log.info("[TrainingQuery] 과정별 회사별 훈련생 수 조회");
        String sql = "SELECT tc.course_name AS 과정명, ts.company AS 회사명, " +
                     "COUNT(*) AS 참여_훈련생수, " +
                     "SUM(CASE WHEN ts.status = '정상수료' THEN 1 ELSE 0 END) AS 수료생수 " +
                     "FROM training_student ts " +
                     "JOIN training_course tc ON ts.course_id = tc.id " +
                     "WHERE ts.company IS NOT NULL " +
                     "GROUP BY tc.id, tc.course_name, ts.company " +
                     "ORDER BY tc.start_date, ts.company";
        return executeTrainingSql(sql);
    }

    /**
     * Tool 6: 2번 이상 수료한 훈련생 명단 조회
     */
    @Tool(description = "2번 이상 과정을 수료한 훈련생 명단을 조회한다. '여러 과정', '중복 수료', '다수 과정' 등의 질문에 사용")
    public String getMultiCourseStudents() {
        log.info("[TrainingQuery] 다중 과정 수료 훈련생 조회");
        String sql = "SELECT ts.student_name AS 이름, " +
                     "MAX(ts.company) AS 회사명, " +
                     "MAX(ts.phone) AS 전화번호, " +
                     "COUNT(*) AS 참여_과정수, " +
                     "STRING_AGG(tc.course_name, ', ' ORDER BY tc.start_date) AS 참여_과정명 " +
                     "FROM training_student ts " +
                     "JOIN training_course tc ON ts.course_id = tc.id " +
                     "WHERE ts.status = '정상수료' " +
                     "GROUP BY ts.student_name " +
                     "HAVING COUNT(*) >= 2 " +
                     "ORDER BY COUNT(*) DESC, ts.student_name";
        return executeTrainingSql(sql);
    }

    /**
     * Tool 7: 특정 훈련생 조회 (이름으로 검색)
     */
    @Tool(description = "특정 훈련생의 수료 여부, 출석률, 참여 과정을 조회한다. 훈련생 이름이 포함된 질문에 사용")
    public String getStudentInfo(
            @org.springframework.ai.tool.annotation.ToolParam(description = "조회할 훈련생 이름") String studentName) {
        log.info("[TrainingQuery] 훈련생 정보 조회: {}", studentName);
        String sql = "SELECT tc.course_name AS 과정명, tc.start_date AS 시작일, " +
                     "ts.status AS 수료여부, ts.attend_days AS 출석일수, " +
                     "ts.absent_days AS 결석일수, ts.attend_rate_day AS 출석률, " +
                     "ts.company AS 회사명 " +
                     "FROM training_student ts " +
                     "JOIN training_course tc ON ts.course_id = tc.id " +
                     "WHERE ts.student_name = '" + studentName + "' " +
                     "ORDER BY tc.start_date";
        return executeTrainingSql(sql);
    }

    /**
     * Tool 8: 특정 훈련생 근태 자료 조회
     */
    @Tool(description = "특정 훈련생의 근태 자료(출석일수, 결석일수, 출석률 등)를 상세 조회한다. '근태', '출석', '결석' 등의 질문에 사용")
    public String getStudentAttendance(
            @org.springframework.ai.tool.annotation.ToolParam(description = "조회할 훈련생 이름") String studentName) {
        log.info("[TrainingQuery] 훈련생 근태 조회: {}", studentName);
        String sql = "SELECT tc.course_name AS 과정명, tc.start_date AS 시작일, tc.end_date AS 종료일, " +
                     "ts.status AS 수료여부, ts.train_days AS 훈련일수, " +
                     "ts.attend_days AS 출석일수, ts.absent_days AS 결석일수, " +
                     "ts.attend_rate_day AS 출석률_일기준, ts.attend_rate_min AS 출석률_분기준 " +
                     "FROM training_student ts " +
                     "JOIN training_course tc ON ts.course_id = tc.id " +
                     "WHERE ts.student_name = '" + studentName + "' " +
                     "ORDER BY tc.start_date";
        return executeTrainingSql(sql);
    }

    /**
     * Tool 9: 출석률 기준 미수료 훈련생 조회
     */
    @Tool(description = "미수료 훈련생 명단을 조회한다. '미수료', '수료 못한', '출석률 낮은' 등의 질문에 사용")
    public String getIncompleteStudents() {
        log.info("[TrainingQuery] 미수료 훈련생 조회");
        String sql = "SELECT tc.course_name AS 과정명, ts.student_name AS 이름, " +
                     "ts.company AS 회사명, ts.attend_rate_day AS 출석률, " +
                     "ts.attend_days AS 출석일수, ts.absent_days AS 결석일수 " +
                     "FROM training_student ts " +
                     "JOIN training_course tc ON ts.course_id = tc.id " +
                     "WHERE ts.status = '미수료' " +
                     "ORDER BY tc.start_date, ts.attend_rate_day";
        return executeTrainingSql(sql);
    }

    /**
     * Tool 10: 훈련생 DB SQL 실행 (자유 질의용)
     * getTrainingSchema()로 스키마 확인 후 생성한 SQL을 실행한다.
     */
    @Tool(description = """
            훈련생 PostgreSQL DB에 SELECT SQL을 실행하고 결과를 반환한다.
            반드시 getTrainingSchema()로 스키마를 먼저 확인한 후 SQL을 전달할 것.
            수료생 수, 명단, 출석률 등 훈련 관련 수치 조회에 사용한다.
            """)
    public String executeTrainingSql(
            @ToolParam(description = "실행할 PostgreSQL SELECT SQL. 세미콜론/바인드 변수 없이 작성") String sql) {

        log.info("[TrainingQuery] SQL: {}", sql);

        if (sql == null || sql.isBlank()) return "SQL이 비어있습니다.";

        // 마크다운 코드블록 및 세미콜론 제거
        sql = sql.replaceAll("```sql", "").replaceAll("```", "").trim();
        sql = sql.replaceAll(";\\s*$", "").trim();

        // 보안 검증
        if (!ALLOWED_PATTERN.matcher(sql).matches()) {
            log.warn("[TrainingQuery] SELECT 아닌 SQL 차단: {}", sql);
            return "SELECT 문만 허용됩니다.";
        }
        if (BLOCKED_PATTERN.matcher(sql).matches()) {
            log.warn("[TrainingQuery] 위험 키워드 차단: {}", sql);
            return "보안상 허용되지 않는 SQL입니다.";
        }

        try {
            List<Map<String, Object>> rows = pgVectorJdbcTemplate.queryForList(sql);
            if (rows.isEmpty()) return "조회 결과가 없습니다.";

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("조회 결과 (%d건):\n\n", rows.size()));
            String header = String.join(" | ", rows.get(0).keySet());
            sb.append(header).append("\n");
            sb.append("-".repeat(Math.min(header.length(), 80))).append("\n");
            for (Map<String, Object> row : rows) {
                String line = row.values().stream()
                        .map(v -> v != null ? v.toString() : "-")
                        .reduce((a, b) -> a + " | " + b).orElse("");
                sb.append(line).append("\n");
            }
            log.info("[TrainingQuery] 조회 완료: {}건", rows.size());
            return sb.toString();

        } catch (Exception e) {
            log.error("[TrainingQuery] SQL 오류: {}", e.getMessage());
            return "SQL 실행 오류: " + e.getMessage();
        }
    }
}