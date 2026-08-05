package com.board.service.chatbot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ================================================================================
 * 회원 프로필 / 게시물 이미지 "선조회(Pre-fetch)" 전용 서비스
 * ================================================================================
 * BoardQueryService(@Tool로 LLM이 직접 호출)와 달리, 본 클래스는 @Tool이 아니라
 * ChatbotService.resolveImageRequest()가 사용자 메시지를 받자마자(LLM 호출 전에)
 * "직접" 호출하는 순수 조회/패턴판별 헬퍼다.
 *
 * [왜 LLM Tool Calling 대신 코드에서 직접 처리하는가]
 *  "내 프로필 사진 보여줘", "OO이 등록한 게시물 이미지" 같은 요청은 패턴이 정형적이라,
 *  LLM에게 매번 Tool 호출을 맡기는 것보다 서비스 코드에서 문자열 패턴으로 즉시 판별하고
 *  DB(jpa_member, jpa_board, jpa_file)에서 바로 URL을 뽑아내는 편이 더 빠르고 정확하다.
 *
 * [메서드 분류]
 *  (A) DB 조회 메서드 - 실제 이미지 URL 목록을 반환
 *      getBoardImageUrls / getMyBoardImageUrls / getMemberProfileImageUrl /
 *      getAllMemberProfileImageUrls / findEmailsByName
 *  (B) 메시지 판별 메서드 - "이 메시지가 어떤 종류의 이미지 요청인지" boolean/String으로 판별
 *      isSelfProfileRequest / isProfileImageRequest / isMyBoardImagesRequest /
 *      extractBoardSeqno / extractMemberName
 *      (원래 정규식(Regex) 기반으로 구현되어 있던 것을 일반 문자열 인덱스 탐색으로
 *       재작성한 것들이며, 각 메서드 주석에 대응되는 원본 정규식을 병기해 두었다)
 *  (C) 내부 헬퍼 - 위 (B)의 세부 토큰/공백/이름 파싱을 담당하는 private 유틸리티
 *
 * [ChatbotService와의 연동]
 *  ChatbotService.resolveImageRequest()가 (1)전체회원 → (2)개별프로필 → (3)내 게시물
 *  → (4)타인 게시물 → (5)재요청 → (6)게시글 번호 순서로 본 클래스의 판별 메서드를
 *  차례로 호출하며, 먼저 매칭되는 조건에서 즉시 결과를 반환한다(수정 E 참고).
 * ================================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoardImageService {

    // 게시판/회원 이미지 URL을 만들기 위한 Oracle DB(jpa_member, jpa_board, jpa_file) 직접 조회용
    private final JdbcTemplate jdbcTemplate;

    private static final Set<String> PRONOUNS =
            Set.of("나", "내", "저", "제", "우리", "본인", "자신", "나의", "저의", "내가", "제가");

    // ===== 정규식을 대체하는 일반 문자열 기반 키워드 목록 =====
    // "나의|저의|내가|제가|나|내|저|제" (SELF_PROFILE_PATTERN, MY_BOARD_IMAGE_PATTERN 의 group1 이었던 목록)
    private static final String[] SELF_WORDS =
            {"나의", "저의", "내가", "제가", "나", "내", "저", "제"};

    // "프로필|프로파일|profile"
    private static final String[] PROFILE_WORDS =
            {"프로필", "프로파일", "profile"};

    // "이미지|사진|photo|image|pic"
    private static final String[] IMAGE_WORDS =
            {"이미지", "사진", "photo", "image", "pic"};

    // "게시판|게시물|등록"
    private static final String[] BOARD_WORDS =
            {"게시판", "게시물", "등록"};

    // "전부|모두|다|전체"
    private static final String[] ALL_WORDS =
            {"전부", "모두", "다", "전체"};

    private static final Set<String> EXCLUDED_NAMES =
            Set.of("이미지", "사진", "프로필", "프로파일", "profile", "image", "photo", "pic");

    /**
     * 게시글 번호(seqno)로 첨부된 이미지 파일들을 jpa_file 테이블에서 조회.
     * 확장자가 이미지(jpg/jpeg/png/gif/webp)인 첨부파일만 최대 4건 반환.
     * ChatbotService.resolveImageRequest()의 (6) "게시글 번호 직접 언급" 케이스에서 호출됨.
     */
    public List<String> getBoardImageUrls(Long seqno) {
        log.info("[BoardImage] 게시물 이미지 조회: seqno={}", seqno);
        try {
            List<Map<String, Object>> files = jdbcTemplate.queryForList(
                    "SELECT fileseqno FROM jpa_file " +
                    "WHERE seqno = ? " +
                    "AND (LOWER(org_filename) LIKE '%.jpg' " +
                    "  OR LOWER(org_filename) LIKE '%.jpeg' " +
                    "  OR LOWER(org_filename) LIKE '%.png' " +
                    "  OR LOWER(org_filename) LIKE '%.gif' " +
                    "  OR LOWER(org_filename) LIKE '%.webp') " +
                    "AND ROWNUM <= 4",
                    seqno);
            List<String> urls = files.stream()
                    .map(f -> "/api/member/image/" + f.get("FILESEQNO"))
                    .collect(Collectors.toList());
            log.info("[BoardImage] 게시물 이미지 {}개 반환", urls.size());
            return urls;
        } catch (Exception e) {
            log.error("[BoardImage] 게시물 이미지 오류", e);
            return new ArrayList<>();
        }
    }

    /**
     * 특정 회원(email)이 등록한 모든 게시물의 첨부 이미지를 jpa_file에서 조회 (최대 12건).
     * ChatbotService.resolveImageRequest()의 (3) "내 게시물 이미지 전체" 및
     * (4) "타인이 등록한 게시물 이미지"(findEmailsByName으로 email을 먼저 찾은 뒤) 케이스에서 호출됨.
     */
    public List<String> getMyBoardImageUrls(String email) {
        log.info("[BoardImage] 게시물 이미지 전체 조회: email={}", email);
        try {
            List<Map<String, Object>> files = jdbcTemplate.queryForList(
                "SELECT fileseqno FROM jpa_file " +
                "WHERE email = ? " +
                "AND (LOWER(org_filename) LIKE '%.jpg' " +
                "  OR LOWER(org_filename) LIKE '%.jpeg' " +
                "  OR LOWER(org_filename) LIKE '%.png' " +
                "  OR LOWER(org_filename) LIKE '%.gif' " +
                "  OR LOWER(org_filename) LIKE '%.webp') " +
                "AND ROWNUM <= 12",
                email);
            List<String> urls = files.stream()
                .map(f -> "/api/member/image/" + f.get("FILESEQNO"))
                .collect(Collectors.toList());
            log.info("[BoardImage] 게시물 이미지 {}개 반환", urls.size());
            return urls;
        } catch (Exception e) {
            log.error("[BoardImage] 게시물 이미지 오류", e);
            return new ArrayList<>();
        }
    }

    /**
     * 이메일/이름/닉네임(emailOrName)으로 회원을 찾아 프로필 이미지 URL을 생성.
     * 1차: email/username/nickname 완전일치 → 2차: username/nickname LIKE 부분일치.
     * stored_filename(실제 파일 존재 여부)은 검사하지 않고 항상 "/api/member/viewProfile/{email}"
     * URL을 만들어 반환하며, 파일이 없으면 프론트/백엔드에서 404 → 기본 이미지로 대체 처리한다.
     * ChatbotService.resolveImageRequest()의 (2) 개별 프로필 요청, (5) 재요청 케이스에서 호출됨.
     */
    public List<String> getMemberProfileImageUrl(String emailOrName) {
        log.info("[BoardImage] 프로필 이미지 조회: '{}'", emailOrName);
        try {
            List<Map<String, Object>> members = jdbcTemplate.queryForList(
                    "SELECT email, username, stored_filename FROM jpa_member " +
                    "WHERE email = ? OR username = ? OR nickname = ?",
                    emailOrName, emailOrName, emailOrName);

            if (members.isEmpty() && !PRONOUNS.contains(emailOrName)) {
                members = jdbcTemplate.queryForList(
                        "SELECT email, username, stored_filename FROM jpa_member " +
                        "WHERE username LIKE ? OR nickname LIKE ?",
                        "%" + emailOrName + "%", "%" + emailOrName + "%");
            }

            if (members.isEmpty()) {
                log.info("[BoardImage] 회원 찾지 못함: '{}'", emailOrName);
                return new ArrayList<>();
            }

            List<String> urls = new ArrayList<>();
            for (Map<String, Object> member : members) {
                String email    = (String) member.get("EMAIL");
                String username = (String) member.get("USERNAME");
                // stored_filename 체크 제거 → 파일 없어도 URL 반환
                // Spring Boot에서 404 반환 → onError에서 기본 이미지 표시
                urls.add("/api/member/viewProfile/" + email);
                log.info("[BoardImage] 프로필 URL 반환: {} ({})", email, username);
            }
            return urls;

        } catch (Exception e) {
            log.error("[BoardImage] 프로필 이미지 오류", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * stored_filename(프로필 사진)이 등록된 전체 회원의 프로필 이미지 URL을 최대 20건까지 조회.
     * ChatbotService.resolveImageRequest()의 (1) "전체 회원 프로필 사진" 일괄 요청 케이스에서 호출됨
     * (isAllMembersProfileRequest()가 true일 때만 도달).
     */
    public List<String> getAllMemberProfileImageUrls() {
        log.info("[BoardImage] 전체 회원 프로필 이미지 조회");
        try {
            List<Map<String, Object>> members = jdbcTemplate.queryForList(
                "SELECT email, username, stored_filename FROM jpa_member " +
                "WHERE stored_filename IS NOT NULL " +
                "AND ROWNUM <= 20"
            );
            //log.info("member 객체 갯수: {}", members.size());
            List<String> urls = new ArrayList<>();
            for (Map<String, Object> member : members) {
                String email = (String) member.get("EMAIL");
                urls.add("/api/member/viewProfile/" + email);
                log.info("[BoardImage] 전체 프로필 URL: {}", email);
            }
            return urls;
        } catch (Exception e) {
            log.error("[BoardImage] 전체 회원 프로필 조회 오류", e);
            return new ArrayList<>();
        }
    }

    /**
     * 이름/닉네임(name)으로 회원의 이메일 목록을 조회 (완전일치 우선, 없으면 LIKE 부분일치).
     * ChatbotService.resolveImageRequest()의 (4) "OO이 등록한 게시물 이미지" 케이스에서
     * extractNameWithPostposition()으로 뽑아낸 이름을 email로 변환하기 위해 호출되며,
     * 반환된 각 email로 getMyBoardImageUrls()를 다시 호출해 이미지를 모은다.
     */
    public List<String> findEmailsByName(String name) {
        try {
            List<Map<String, Object>> members = jdbcTemplate.queryForList(
                "SELECT email FROM jpa_member " +
                "WHERE username = ? OR nickname = ?",
                name, name);
            if (members.isEmpty()) {
                members = jdbcTemplate.queryForList(
                    "SELECT email FROM jpa_member " +
                    "WHERE username LIKE ? OR nickname LIKE ?",
                    "%" + name + "%", "%" + name + "%");
            }
            return members.stream()
                .map(m -> (String) m.get("EMAIL"))
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("[BoardImage] 이름으로 이메일 조회 오류", e);
            return new ArrayList<>();
        }
    }

    /**
     * "나의 ... 프로필 (사진/이미지)" 처럼 자기 자신을 가리키는 단어 뒤(30자 이내)에
     * 프로필 키워드가 등장하는지 확인.
     * 원본 정규식: (나의|저의|내가|제가|나|내|저|제).{0,30}(프로필|프로파일|profile)(이미지|사진|...)?
     */
    public boolean isSelfProfileRequest(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();

        for (String self : SELF_WORDS) {
            int idx = 0;
            while ((idx = lower.indexOf(self, idx)) != -1) {
                int from = idx + self.length();
                if (profileWordWithin(lower, from, 30)) {
                    return true;
                }
                idx += self.length();
            }
        }
        return false;
    }

    /**
     * 프로필/프로파일/profile 키워드 존재 여부.
     * 원본 정규식(PROFILE_KEYWORD)은 첫 번째 대안에서 뒤따르는 이미지 단어를 선택적으로 처리하므로
     * 실질적으로 "프로필 계열 단어가 존재하는지"와 동일하다.
     */
    public boolean isProfileImageRequest(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        for (String p : PROFILE_WORDS) {
            if (lower.contains(p.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 원본 정규식(MY_BOARD_IMAGE_PATTERN):
     *   (자기자신 단어).{0,10}(게시판|게시물|등록).{0,10}(이미지 계열 단어)
     *   또는
     *   (이미지 계열 단어).{0,5}(전부|모두|다|전체)
     */
    public boolean isMyBoardImagesRequest(String message) {
        if (message == null) return false;

        boolean hasPronoun = PRONOUNS.stream().anyMatch(message::contains);
        if (!hasPronoun) return false;

        String lower = message.toLowerCase();

        // 대안1: 자기자신 단어 → (10자 이내) 게시판 관련 단어 → (10자 이내) 이미지 단어
        for (String self : SELF_WORDS) {
            int selfIdx = 0;
            while ((selfIdx = lower.indexOf(self, selfIdx)) != -1) {
                int afterSelf = selfIdx + self.length();
                for (String board : BOARD_WORDS) {
                    int boardIdx = lower.indexOf(board, afterSelf);
                    if (boardIdx != -1 && boardIdx <= afterSelf + 10) {
                        int afterBoard = boardIdx + board.length();
                        if (profileImageWordWithin(lower, afterBoard, 10)) {
                            return true;
                        }
                    }
                }
                selfIdx += self.length();
            }
        }

        // 대안2: 이미지 단어 → (5자 이내) 전부/모두/다/전체
        for (String image : IMAGE_WORDS) {
            int imageIdx = 0;
            while ((imageIdx = lower.indexOf(image, imageIdx)) != -1) {
                int afterImage = imageIdx + image.length();
                for (String all : ALL_WORDS) {
                    int allIdx = lower.indexOf(all, afterImage);
                    if (allIdx != -1 && allIdx <= afterImage + 5) {
                        return true;
                    }
                }
                imageIdx += image.length();
            }
        }

        return false;
    }

    /**
     * 원본 정규식(BOARD_IMAGE_PATTERN):
     *   게시물\s*(\d+)번? | \b(\d+)번\s*게시물 | seqno[:\s]*(\d+)
     * 참고: 원본은 정규식 엔진이 세 대안 중 문자열에서 가장 먼저(왼쪽) 매치되는 것을 찾지만,
     * 아래 일반 구현은 "게시물" → "N번 게시물" → "seqno" 순으로 우선순위를 두고 검사한다.
     * (한 메시지에 번호 언급이 하나뿐인 일반적인 경우라면 결과는 동일하다.)
     */
    public Long extractBoardSeqno(String message) {
        if (message == null) return null;
        String lower = message.toLowerCase();

        // 패턴1: "게시물" 뒤에 오는 숫자 (예: "게시물 5번", "게시물5")
        int idx = lower.indexOf("게시물");
        if (idx != -1) {
            String digits = extractDigitsAfter(message, idx + "게시물".length());
            if (digits != null) {
                return Long.parseLong(digits);
            }
        }

        // 패턴2: "N번 게시물" (예: "5번 게시물")
        Long fromBeon = extractNumberBeforeBeonBoard(message);
        if (fromBeon != null) {
            return fromBeon;
        }

        // 패턴3: "seqno" 뒤에 오는 숫자 (콜론/공백 허용)
        idx = lower.indexOf("seqno");
        if (idx != -1) {
            int from = idx + "seqno".length();
            while (from < message.length()
                    && (message.charAt(from) == ':' || Character.isWhitespace(message.charAt(from)))) {
                from++;
            }
            String digits = extractDigitsAt(message, from);
            if (digits != null) {
                return Long.parseLong(digits);
            }
        }

        return null;
    }

    /**
     * 원본 정규식(NAME_BEFORE_PROFILE / MEMBER_NAME_PATTERN)을 대체하는 이름 추출 로직.
     */
    public String extractMemberName(String message) {
        if (message == null) return null;

        String name = extractNameBeforeProfile(message);
        if (name != null && !name.isBlank() && !PRONOUNS.contains(name)) {
            log.info("[BoardImage] 이름 추출(패턴1): '{}'", name);
            return name;
        }

        name = extractNameBeforeSuffix(message);
        if (name != null && !PRONOUNS.contains(name)) {
            log.info("[BoardImage] 이름 추출(패턴2): '{}'", name);
            return name;
        }

        log.info("[BoardImage] 이름 추출 실패 또는 대명사: '{}'", message);
        return null;
    }

    // ===================== 내부 헬퍼 (정규식 대체 로직) =====================

    /** lower 문자열의 from 위치부터 maxGap 글자 이내에서 프로필 단어가 시작되는지 확인 */
    private boolean profileWordWithin(String lower, int from, int maxGap) {
        if (from < 0) return false;
        for (String p : PROFILE_WORDS) {
            int idx = lower.indexOf(p.toLowerCase(), from);
            if (idx != -1 && idx <= from + maxGap) {
                return true;
            }
        }
        return false;
    }

    /** lower 문자열의 from 위치부터 maxGap 글자 이내에서 이미지 단어가 시작되는지 확인 */
    private boolean profileImageWordWithin(String lower, int from, int maxGap) {
        if (from < 0) return false;
        for (String img : IMAGE_WORDS) {
            int idx = lower.indexOf(img.toLowerCase(), from);
            if (idx != -1 && idx <= from + maxGap) {
                return true;
            }
        }
        return false;
    }

    /** from 위치부터 공백을 건너뛴 뒤 이어지는 숫자를 추출 (\s*(\d+) 대응) */
    private String extractDigitsAt(String message, int from) {
        if (from < 0 || from > message.length()) return null;
        int i = from;
        int start = i;
        int end = i;
        while (end < message.length() && Character.isDigit(message.charAt(end))) {
            end++;
        }
        if (end > start) {
            return message.substring(start, end);
        }
        return null;
    }

    /** from 위치부터 공백을 건너뛴 뒤 이어지는 숫자를 추출 (게시물\s*(\d+) 대응) */
    private String extractDigitsAfter(String message, int from) {
        int i = from;
        while (i < message.length() && Character.isWhitespace(message.charAt(i))) {
            i++;
        }
        return extractDigitsAt(message, i);
    }

    /** "N번 게시물" 형태를 찾아 숫자를 반환 (\b(\d+)번\s*게시물 대응) */
    private Long extractNumberBeforeBeonBoard(String message) {
        int len = message.length();
        int i = 0;
        while (i < len) {
            char c = message.charAt(i);
            if (Character.isDigit(c)) {
                // 단어 경계 근사치: 바로 앞 문자가 문자/숫자이면 새로운 숫자의 시작이 아님
                boolean boundaryOk = (i == 0) || !Character.isLetterOrDigit(message.charAt(i - 1));
                int start = i;
                int j = i;
                while (j < len && Character.isDigit(message.charAt(j))) {
                    j++;
                }
                int digitsEnd = j;
                if (boundaryOk && digitsEnd < len && message.charAt(digitsEnd) == '번') {
                    int after = digitsEnd + 1;
                    while (after < len && Character.isWhitespace(message.charAt(after))) {
                        after++;
                    }
                    if (message.startsWith("게시물", after)) {
                        return Long.parseLong(message.substring(start, digitsEnd));
                    }
                }
                i = digitsEnd;
            } else {
                i++;
            }
        }
        return null;
    }

    /** 공백 위치까지 건너뛰기 */
    private int skipWhitespace(String message, int pos) {
        int i = pos;
        while (i < message.length() && Character.isWhitespace(message.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isHangulSyllable(char c) {
        return c >= 0xAC00 && c <= 0xD7A3;
    }

    private static boolean isLatinNameChar(char c, boolean allowAt) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || (allowAt && c == '@');
    }

    /**
     * 위치 i에서 시작하는 이름 후보 토큰을 읽음.
     * 한글은 2~5자, 영숫자(._@)는 2~30자까지 (정규식의 {2,5}, {2,30}과 동일한 상한).
     * 반환값: {시작인덱스, 끝인덱스(exclusive)} 또는 매칭 실패 시 null.
     */
    private int[] readNameToken(String message, int i, boolean allowAt) {
        int len = message.length();

        if (i < len && isHangulSyllable(message.charAt(i))) {
            int j = i;
            while (j < len && isHangulSyllable(message.charAt(j)) && (j - i) < 5) {
                j++;
            }
            if (j - i >= 2) {
                return new int[]{i, j};
            }
        }

        if (i < len && isLatinNameChar(message.charAt(i), allowAt)) {
            int j = i;
            while (j < len && isLatinNameChar(message.charAt(j), allowAt) && (j - i) < 30) {
                j++;
            }
            if (j - i >= 2) {
                return new int[]{i, j};
            }
        }

        return null;
    }

    /** pos 위치에 프로필 단어(또는 "이미지 단어 + 프로필 단어" 조합)가 시작되는지 확인 */
    private boolean startsWithProfileWord(String message, int pos) {
        if (pos < 0 || pos > message.length()) return false;
        String lowerSuffix = message.substring(pos).toLowerCase();

        for (String p : PROFILE_WORDS) {
            if (lowerSuffix.startsWith(p.toLowerCase())) {
                return true;
            }
        }

        // "이미지 프로필" 처럼 이미지 단어가 먼저 오고 프로필 단어가 뒤따르는 경우
        for (String img : IMAGE_WORDS) {
            String imgLower = img.toLowerCase();
            if (lowerSuffix.startsWith(imgLower)) {
                int after = imgLower.length();
                while (after < lowerSuffix.length() && Character.isWhitespace(lowerSuffix.charAt(after))) {
                    after++;
                }
                String rest = lowerSuffix.substring(after);
                for (String p : PROFILE_WORDS) {
                    if (rest.startsWith(p.toLowerCase())) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /** 이름 뒤에 붙은 "회원", "님", "의" 접미사를 제거 (replaceAll("(회원|님|의)$","") 대응) */
    private String stripTrailingSuffix(String name) {
        for (String suffix : new String[]{"회원", "님", "의"}) {
            if (name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length()).trim();
            }
        }
        return name.trim();
    }

    /**
     * 원본 NAME_BEFORE_PROFILE 정규식 대응:
     * 이름 + (선택)공백+(회원|님) + (선택)공백+의 + 공백* + 프로필 키워드
     */
    private String extractNameBeforeProfile(String message) {
        int len = message.length();
        int i = 0;
        while (i < len) {
            int[] nameSpan = readNameToken(message, i, true); // '@' 허용 (이메일 형태 이름 포함)
            if (nameSpan != null) {
                int nameStart = nameSpan[0];
                int nameEnd = nameSpan[1];

                int pos = skipWhitespace(message, nameEnd);
                if (message.startsWith("회원", pos)) {
                    pos += 2;
                } else if (message.startsWith("님", pos)) {
                    pos += 1;
                }

                pos = skipWhitespace(message, pos);
                if (message.startsWith("의", pos)) {
                    pos += 1;
                }

                pos = skipWhitespace(message, pos);
                if (startsWithProfileWord(message, pos)) {
                    String name = stripTrailingSuffix(message.substring(nameStart, nameEnd));
                    if (!name.isBlank()) {
                        return name;
                    }
                }
            }
            i++;
        }
        return null;
    }

    /**
     * 원본 MEMBER_NAME_PATTERN 정규식 대응:
     * 이름 + 공백* + (님|회원)
     */
    private String extractNameBeforeSuffix(String message) {
        int len = message.length();
        int i = 0;
        while (i < len) {
            int[] nameSpan = readNameToken(message, i, false); // '@' 불허
            if (nameSpan != null) {
                int pos = skipWhitespace(message, nameSpan[1]);
                if (message.startsWith("님", pos) || message.startsWith("회원", pos)) {
                    String name = message.substring(nameSpan[0], nameSpan[1]).trim();
                    if (!name.isEmpty()) {
                        return name;
                    }
                }
            }
            i++;
        }
        return null;
    }
}