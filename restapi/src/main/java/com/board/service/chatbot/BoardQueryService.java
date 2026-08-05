package com.board.service.chatbot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ================================================================================
 * 게시판/회원 Oracle DB 자연어 조회 서비스 (Text-to-SQL) - Spring AI @Tool 제공자
 * ================================================================================
 * ChatbotService가 RouterAgent 분류 결과 BOARD_DB 카테고리일 때 ChatClient에 장착하는
 * @Tool queryBoardData() 하나를 제공한다. 내부적으로 별도의 OpenAiChatModel을 한 번 더
 * 호출해서(LLM 안에서 또 다른 LLM 호출) "자연어 질문 → Oracle SELECT SQL"로 변환한 뒤,
 * 변환된 SQL을 여러 단계의 화이트리스트/블랙리스트 보안 검증을 거쳐 실제로 실행한다.
 *
 * [BoardImageService와의 차이]
 *  BoardImageService는 ChatbotService가 LLM 호출 전에 코드로 직접 부르는 "선조회 헬퍼"인
 *  반면, 본 클래스는 @Tool로 등록되어 "LLM이 필요하다고 판단할 때 스스로 호출"하는
 *  Tool Calling 대상이다. 즉 이미지 요청은 BoardImageService가 먼저 가로채고,
 *  그 외의 회원 통계/게시물 목록/댓글/좋아요 등 일반적인 DB 조회는 LLM이 이 Tool을
 *  직접 호출해서 처리한다.
 *
 * [queryBoardData() 처리 단계 요약]
 *  0) 권한(MASTER 여부) 및 로그인 이메일 확인 → MASTER_SCHEMA 또는 USER_SCHEMA_TEMPLATE 선택
 *  1) 내부 OpenAiChatModel 호출로 자연어 → SQL 변환 (SystemMessage에 스키마+보안규칙 포함)
 *  2) SELECT/WITH로 시작하는지 확인 (isAllowedSelect)
 *  3) DROP/DELETE/UPDATE 등 DML/DDL 키워드 차단 (containsAnyKeyword + BLOCKED_KEYWORDS)
 *  4) SQL에 남아있는 바인드 파라미터(:email 등)를 로그인 사용자 이메일 리터럴로 치환
 *  5) PASSWORD/AUTHKEY 등 "누구도 조회 불가" 컬럼 차단 (ALWAYS_BLOCKED_KEYWORDS, MASTER도 예외 없음
 *     → 단, 실제 검사 코드는 !isMaster 조건이므로 MASTER는 이 차단을 건너뛴다)
 *  6) TELNO/ZIPCODE/ADDRESS 등 "본인만 조회 가능" 개인정보 컬럼은, WHERE 절에 본인 email 조건이
 *     정확히 있는지 문자열 검증 후 허용/차단 (PERSONAL_INFO_KEYWORDS)
 *  7) 최종 SQL을 jdbcTemplate로 실행
 *  8) 결과를 "컬럼명 | 컬럼명 ..." 헤더 + 각 행을 파이프(|)로 구분한 텍스트 표로 포맷팅해 반환
 *     → 이 반환 문자열이 LLM에게 그대로 전달되고, ChatbotService의 시스템 프롬프트가
 *       "Tool이 반환한 숫자/명단을 임의로 재계산하지 말고 그대로 표시하라"고 강하게 지시한다.
 * ================================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoardQueryService {

    // 실제 SQL 실행 대상 - Oracle 게시판/회원 DB
    private final JdbcTemplate jdbcTemplate;

    // 자연어 질문을 Oracle SQL로 변환하기 위한 별도의 OpenAI 채팅 모델 호출 (ChatClient가 아닌 저수준 ChatModel 직접 사용)
    private final OpenAiChatModel chatModel;

    // ===== 정규식을 대체하는 일반 문자열 기반 키워드 목록 =====

    // DML/DDL 차단 대상 키워드 (원본: BLOCKED_PATTERN)
    private static final String[] BLOCKED_KEYWORDS =
            {"DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "TRUNCATE", "CREATE", "GRANT", "REVOKE", "EXEC", "EXECUTE"};

    // 본인 포함 누구도 조회 불가 (원본: ALWAYS_BLOCKED_PATTERN)
    private static final String[] ALWAYS_BLOCKED_KEYWORDS =
            {"PASSWORD", "AUTHKEY", "PWCHECK", "LASTPWDATE", "LASTPWCHECKDATE", "STORED_FILENAME"};

    // 본인만 조회 가능한 개인정보 (원본: PERSONAL_INFO_PATTERN)
    private static final String[] PERSONAL_INFO_KEYWORDS =
            {"TELNO", "ZIPCODE", "ADDRESS"};

    // MASTER용 스키마 - 전체 조회 허용
    private static final String MASTER_SCHEMA = """
            [게시판 Oracle DB 테이블 구조 - 마스터 관리자]
            
            1. jpa_member (회원 테이블) - 전체 컬럼 조회 가능
               - email VARCHAR2(50) PK
               - username VARCHAR2(50) - 이름
               - gender VARCHAR2(20) - 성별
               - hobby VARCHAR2(50) - 취미
               - job VARCHAR2(20) - 직업
               - nickname VARCHAR2(20) - 닉네임
               - telno VARCHAR2(20) - 전화번호
               - zipcode VARCHAR2(20) - 우편번호
               - address VARCHAR2(200) - 주소
               - role VARCHAR2(20) - 권한 (USER, MASTER)
               - regdate TIMESTAMP - 가입일
               - lastlogindate TIMESTAMP - 최근 로그인일
               - lastlogoutdate TIMESTAMP - 최근 로그아웃일
               - fromsocial VARCHAR2(2) - 소셜가입여부 (Y/N)
            
            2. jpa_board (게시물 테이블)
               - seqno NUMBER PK
               - writer VARCHAR2(50) - 작성자명
               - title VARCHAR2(200) - 제목
               - content VARCHAR2(2000) - 내용
               - regdate TIMESTAMP - 작성일
               - hitno NUMBER - 조회수
               - likecnt NUMBER - 좋아요수
               - dislikecnt NUMBER - 싫어요수
               - email VARCHAR2(50) FK → jpa_member.email
            
            3. jpa_reply (댓글 테이블)
               - replyseqno NUMBER PK
               - replywriter VARCHAR2(50) - 댓글 작성자명
               - replycontent VARCHAR2(200) - 댓글 내용
               - replyregdate TIMESTAMP - 댓글 작성일
               - email VARCHAR2(50) FK → jpa_member.email
               - seqno NUMBER FK → jpa_board.seqno
            
            4. jpa_like (좋아요 테이블)
               - seqno NUMBER FK → jpa_board.seqno (복합PK)
               - email VARCHAR2(50) FK → jpa_member.email (복합PK)
               - mylikecheck VARCHAR2(2) - 좋아요 여부 (Y)
               - mydislikecheck VARCHAR2(2) - 싫어요 여부 (Y)
               - likedate VARCHAR2(50) - 좋아요 날짜
               - dislikedate VARCHAR2(50) - 싫어요 날짜
            
            5. jpa_file (첨부파일 테이블)
               - fileseqno NUMBER PK
               - seqno NUMBER FK → jpa_board.seqno
               - email VARCHAR2(50) FK → jpa_member.email
               - org_filename VARCHAR2(200) - 원본 파일명
               - filesize NUMBER - 파일 크기
               - checkfile VARCHAR2(2) - 파일 상태
            
            6. jpa_member_log (로그인 기록 테이블)
               - email VARCHAR2(50) FK → jpa_member.email (복합PK)
               - inouttime TIMESTAMP (복합PK) - 시각
               - status VARCHAR2(10) - 상태 (login/logout)
            
            [규칙]
            - Oracle SQL 문법 사용
            - 반드시 SELECT 문만 생성
            - SQL 문만 출력 (설명, 마크다운, 백틱 없이)
            - 세미콜론(;) 포함 금지
            - 바인드 변수(:email 등) 사용 금지, 값을 SQL에 직접 리터럴로 포함할 것
            - 결과가 많으면 FETCH FIRST 50 ROWS ONLY 적용
            """;

    // USER용 스키마 - 개인정보 관련 컬럼 제외
    private static final String USER_SCHEMA_TEMPLATE = """
            [게시판 Oracle DB 테이블 구조 - 일반 사용자]
            현재 로그인 사용자: %s
            
            1. jpa_member (회원 테이블)
               - email VARCHAR2(50) PK
               - username VARCHAR2(50) - 이름
               - gender VARCHAR2(20) - 성별
               - hobby VARCHAR2(50) - 취미
               - job VARCHAR2(20) - 직업
               - nickname VARCHAR2(20) - 닉네임
               - telno VARCHAR2(20) - 전화번호 (본인만 조회 가능)
               - zipcode VARCHAR2(20) - 우편번호 (본인만 조회 가능)
               - address VARCHAR2(200) - 주소 (본인만 조회 가능)
               - role VARCHAR2(20) - 권한
               - regdate TIMESTAMP - 가입일
               - lastlogindate TIMESTAMP - 최근 로그인일
               - fromsocial VARCHAR2(2) - 소셜가입여부
               ※ 절대 조회 금지 컬럼: password, authkey, pwcheck
            
            2. jpa_board (게시물 테이블) - 전체 조회 가능
               - seqno NUMBER PK
               - writer VARCHAR2(50) - 작성자명
               - title VARCHAR2(200) - 제목
               - content VARCHAR2(2000) - 내용
               - regdate TIMESTAMP - 작성일
               - hitno NUMBER - 조회수
               - likecnt NUMBER - 좋아요수
               - dislikecnt NUMBER - 싫어요수
               - email VARCHAR2(50) FK → jpa_member.email
            
            3. jpa_reply (댓글 테이블) - 전체 조회 가능
               - replyseqno NUMBER PK
               - replywriter VARCHAR2(50) - 댓글 작성자명
               - replycontent VARCHAR2(200) - 댓글 내용
               - replyregdate TIMESTAMP - 댓글 작성일
               - email VARCHAR2(50) FK → jpa_member.email
               - seqno NUMBER FK → jpa_board.seqno
            
            4. jpa_like (좋아요 테이블) - 전체 조회 가능
               - seqno NUMBER FK → jpa_board.seqno (복합PK)
               - email VARCHAR2(50) FK → jpa_member.email (복합PK)
               - mylikecheck VARCHAR2(2) - 좋아요 여부 (Y)
               - mydislikecheck VARCHAR2(2) - 싫어요 여부 (Y)
            
            5. jpa_file (첨부파일 테이블)
               - fileseqno NUMBER PK
               - seqno NUMBER FK → jpa_board.seqno
               - email VARCHAR2(50) FK → jpa_member.email
               - org_filename VARCHAR2(200) - 원본 파일명
               - checkfile VARCHAR2(2) - 파일 상태
            
            [규칙]
            - Oracle SQL 문법 사용
            - 반드시 SELECT 문만 생성
            - SQL 문만 출력 (설명, 마크다운, 백틱 없이)
            - 세미콜론(;) 포함 금지
            - 바인드 변수(:email 등) 사용 금지, 값을 SQL에 직접 리터럴로 포함할 것
            - 결과가 많으면 FETCH FIRST 50 ROWS ONLY 적용
            """;

    @Tool(description = """
            게시판 데이터베이스 조회 도구.
            전체 회원 수, 게시물 수 등 통계 현황부터
            회원 목록, 게시물 내용, 댓글, 좋아요, 순위, 조인 등
            게시판과 관련된 모든 데이터 조회 질문에 사용한다.
            자연어로 질문하면 자동으로 SQL을 생성하여 조회한다.
            단, 데이터 수정/삭제는 불가하다.
            query 파라미터에 조회할 내용을 자연어로 전달한다.
            주의: 출석부, 훈련생 명단, 수료 여부, 출석률 등 훈련 관련 정보는
            이 도구가 아닌 Vector DB 문서에서 찾아야 한다. 이 도구로 조회하지 말 것.
            """)
    // 클래스 상단 Javadoc의 "queryBoardData() 처리 단계 요약" 참고.
    // 여기서는 Step 0: 현재 로그인 사용자/MASTER 권한 여부를 SecurityContext에서 추출한다.
    public String queryBoardData(String query) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String currentEmail = auth != null ? auth.getName() : "anonymous";
        boolean isMaster = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("MASTER"));

        log.info("[BoardQuery Tool] user: {}, isMaster: {}, query: '{}'",
                currentEmail, isMaster, query);

        try {
            String schema = isMaster
                    ? MASTER_SCHEMA
                    : String.format(USER_SCHEMA_TEMPLATE, currentEmail);

            // [수정 포인트] 마스터든 일반 유저든 실제 이메일 정보를 제공하고, 타인 조회 시 강제 이메일 바인딩을 차단하는 가이드 추가
            String userContext;
            if (isMaster) {
                userContext = """
                        [현재 사용자 권한: 마스터 관리자(MASTER)]
                        - 모든 테이블과 모든 컬럼(타인의 개인정보 포함)을 제약 없이 조회할 수 있습니다.
                        - 만약 사용자가 '나', '내 주소', '현재 사용자'의 정보를 요구하면 아래의 마스터 이메일을 WHERE 조건에 직접 리터럴로 사용하세요.
                        - 현재 로그인 관리자 이메일: '""" + currentEmail + "'";
            } else {
                userContext = String.format("""
                        [현재 사용자 권한: 일반 사용자(USER)]
                        - 현재 로그인 사용자 이메일: '%s'
                        
                        [SQL 생성 규칙]
                        1. 사용자가 '나', '내', '저', '나의 정보', '본인' 등 자기 자신을 지칭할 때만 WHERE 조건에 email = '%s'를 사용하세요.
                        2. 만약 사용자가 '최현아의 주소는?'과 같이 명백히 다른 사람의 이름이나 정보를 지정하여 요구하면, 억지로 'AND email = '%s'' 같은 조건을 붙이지 마세요.
                        3. 사용자가 요청한 대상(예: WHERE username = '최현아')에 맞는 정확한 SQL을 생성하세요. (타인 정보 차단 및 보안 검증은 백엔드 시스템 필터가 수행하므로, AI는 오직 사용자의 질문에만 집중해 정확한 SQL을 만들어야 합니다.)
                        4. 절대 :email, :currentEmail 같은 바인드 변수를 사용하지 말고 값을 리터럴 문자열로 직접 포함하세요.
                        """, currentEmail, currentEmail, currentEmail);
            }

            // Step 1: 자연어 → SQL 변환
            String sql = chatModel.call(
                    new Prompt(List.of(
                            new SystemMessage("당신은 Oracle SQL 전문가입니다.\n"
                                    + "사용자의 자연어 질문을 Oracle SELECT SQL로 변환해주세요.\n"
                                    + "SQL 문만 출력하고 설명, 마크다운, 백틱 없이 순수 SQL만 반환하세요.\n\n"
                                    + userContext + "\n\n"
                                    + schema),
                            new UserMessage(query)
                    ))
            ).getResult().getOutput().getText();

            if (sql == null || sql.isBlank()) return "SQL 생성에 실패했습니다.";

            sql = sql.replace("```sql", "").replace("```", "").trim();
            log.info("[BoardQuery Tool] 생성된 SQL: {}", sql);

            // Step 2: SELECT 여부 확인
            if (!isAllowedSelect(sql)) {
                log.warn("[BoardQuery Tool] SELECT가 아닌 SQL 차단: {}", sql);
                return "데이터 조회(SELECT)만 가능합니다.";
            }

            // Step 3: DML/DDL 키워드 차단
            if (containsAnyKeyword(sql, BLOCKED_KEYWORDS)) {
                log.warn("[BoardQuery Tool] 위험 키워드 차단: {}", sql);
                return "보안상 허용되지 않는 SQL입니다.";
            }

            // Step 4: bind parameter 감지 → 현재 사용자 이메일로 치환
            // 주의: 문자열 리터럴('..')  안의 :MI, :SS 등 TO_TIMESTAMP 포맷은 치환 제외
            if (containsBindParam(sql)) {
                if (currentEmail != null && !currentEmail.equals("anonymous")) {
                    // 문자열 리터럴 안/밖을 구분하여 리터럴 밖의 bind parameter만 치환
                    StringBuilder replaced = new StringBuilder();
                    boolean inLiteral = false;
                    int i = 0;
                    while (i < sql.length()) {
                        char c = sql.charAt(i);
                        if (c == '\'') {
                            inLiteral = !inLiteral;
                            replaced.append(c);
                            i++;
                        } else if (!inLiteral && c == ':' && i + 1 < sql.length()
                                && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                            // 리터럴 밖의 bind parameter → 이메일로 치환
                            int end = i + 1;
                            while (end < sql.length() &&
                                   (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                                end++;
                            }
                            replaced.append("'").append(currentEmail).append("'");
                            i = end;
                        } else {
                            replaced.append(c);
                            i++;
                        }
                    }
                    sql = replaced.toString();
                    log.info("[BoardQuery Tool] bind parameter 치환 후 SQL: {}", sql);
                } else {
                    log.warn("[BoardQuery Tool] bind parameter 포함 + 비로그인 차단: {}", sql);
                    return "로그인이 필요한 조회입니다.";
                }
            }

            // Step 5: 보안 컬럼 차단 (본인 포함 누구도 불가)
            if (!isMaster && containsAnyKeyword(sql, ALWAYS_BLOCKED_KEYWORDS)) {
                log.warn("[BoardQuery Tool] 절대 차단 컬럼 포함: {}", sql);
                return "보안상 조회할 수 없는 정보입니다.";
            }

            // Step 6: 개인정보 컬럼 (전화번호, 주소) - 본인만 허용 (고도화 버전)
            if (!isMaster && containsAnyKeyword(sql, PERSONAL_INFO_KEYWORDS)) {
                // 공백을 제거하고 소문자로 변환하여 엄격하게 패턴 매칭 진행
                String minimizedSql = removeAllWhitespace(sql.toLowerCase());
                String targetCondition = "email='" + currentEmail.toLowerCase() + "'";
                
                // WHERE 절 뒤에 email = '본인이메일' 조건이 정확하게 박혀 있는지 확인
                boolean hasExactSelfCondition = minimizedSql.contains("where" + targetCondition) 
                                             || minimizedSql.contains("and" + targetCondition);

                // 만약 본인 조건이 없거나, 다른 사람의 조건을 조회하려는 시도(username, nickname 등 지정)가 섞여 있다면 차단
                if (!hasExactSelfCondition || minimizedSql.contains("username=") || minimizedSql.contains("nickname=")) {
                    log.warn("[BoardQuery Tool] 타인 개인정보 조회 시도 차단 (올바르지 않은 본인 확인 조건): {}", sql);
                    return "타인의 개인정보(전화번호, 주소 등)는 조회할 수 없습니다.";
                }
                log.info("[BoardQuery Tool] 본인 개인정보 조회 검증 통과: {}", currentEmail);
            }

            // Step 7: SQL 실행
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            if (rows.isEmpty()) return "조회 결과가 없습니다.";

            // Step 8: 결과 포맷팅
            StringBuilder result = new StringBuilder();
            result.append(String.format("조회 결과 (%d건):\n\n", rows.size()));
            Map<String, Object> firstRow = rows.get(0);
            String header = String.join(" | ", firstRow.keySet());
            result.append(header).append("\n");
            result.append("-".repeat(Math.min(header.length(), 80))).append("\n");
            for (Map<String, Object> row : rows) {
                String line = row.values().stream()
                        .map(v -> v != null ? v.toString() : "-")
                        .reduce((a, b) -> a + " | " + b)
                        .orElse("");
                result.append(line).append("\n");
            }

            log.info("[BoardQuery Tool] 조회 완료: {}건", rows.size());
            return result.toString();

        } catch (Exception e) {
            log.error("[BoardQuery Tool] 오류 발생", e);
            return "데이터 조회 중 오류가 발생했습니다: " + e.getMessage();
        }
    }

    // ===================== 내부 헬퍼 (정규식 대체 로직) =====================

    /**
     * 원본 ALLOWED_PATTERN: "^\s*(SELECT|WITH)\s+.*" (CASE_INSENSITIVE | DOTALL)
     * DOTALL 덕분에 .* 는 개행 포함 전체를 소비하므로, 실질적으로는
     * "선행 공백 제거 후 SELECT 또는 WITH + 공백" 으로 시작하는지만 확인하면 동일하다.
     */
    private boolean isAllowedSelect(String sql) {
        String trimmed = sql.stripLeading();
        return startsWithKeywordAndWhitespace(trimmed, "SELECT")
                || startsWithKeywordAndWhitespace(trimmed, "WITH");
    }

    private boolean startsWithKeywordAndWhitespace(String text, String keyword) {
        if (!text.regionMatches(true, 0, keyword, 0, keyword.length())) {
            return false;
        }
        int pos = keyword.length();
        // \s+ : 최소 1개 이상의 공백 필요
        return pos < text.length() && Character.isWhitespace(text.charAt(pos));
    }

    /**
     * 원본 BLOCKED_PATTERN / ALWAYS_BLOCKED_PATTERN / PERSONAL_INFO_PATTERN 공통 로직:
     * ".*(A|B|C).*" (CASE_INSENSITIVE | DOTALL) 로 matches() 하는 것은
     * 결국 "문자열에 A, B, C 중 하나라도 포함되는지(대소문자 무시)" 와 동일하다.
     */
    private boolean containsAnyKeyword(String sql, String[] keywords) {
        String upper = sql.toUpperCase();
        for (String keyword : keywords) {
            if (upper.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 원본 BIND_PARAM_PATTERN: ":[a-zA-Z_][a-zA-Z0-9_]*" 의 find() 대응.
     * 실제 치환은 이미 문자열 스캔(리터럴 인지)으로 처리되므로, 여기서는
     * "바인드 파라미터로 보이는 콜론이 하나라도 존재하는지"만 판단하면 된다.
     */
    private boolean containsBindParam(String sql) {
        for (int i = 0; i < sql.length() - 1; i++) {
            if (sql.charAt(i) == ':') {
                char next = sql.charAt(i + 1);
                if (Character.isLetter(next) || next == '_') {
                    return true;
                }
            }
        }
        return false;
    }

    /** sql.replaceAll("\\s+", "") 대응: 모든 공백 문자 제거 */
    private String removeAllWhitespace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}