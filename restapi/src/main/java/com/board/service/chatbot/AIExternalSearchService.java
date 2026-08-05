// AIExternalSearchService.java

package com.board.service.chatbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * ================================================================================
 * 외부 정보 연동 서비스 (뉴스 검색 / 이미지 검색 / 날씨) - Spring AI @Tool 제공자
 * ================================================================================
 * ChatbotService가 RouterAgent 분류 결과에 따라(WEB_SEARCH, SECRETARY, 그리고
 * ChatClient에 전역 defaultTools로 등록되는 경우 GENERAL_CHAT까지) LLM에게 장착해주는
 * 3종류의 @Tool 메서드를 제공한다. LLM이 스스로 필요하다고 판단하면 이 메서드들을
 * "함수 호출(Tool Calling)" 형태로 실행하고, 그 반환 문자열을 자기 답변에 반영한다.
 *
 * @Tool searchWebAndNews()   : 네이버 뉴스 검색 + (필요 시) 네이버 이미지 소수 곁들임
 * @Tool searchImages()       : 네이버 이미지 전용 검색 - 뉴스 검색 없이 장수(count) 지정 가능 [신규]
 * @Tool searchLocalPlaces()  : 네이버 지역(장소) 검색 - 병원/맛집/약국 등 POI 조회 [2026-07-15 신규]
 * @Tool getWeatherInfo()     : 기상청 단기예보 - 초단기실황(현재 날씨)
 * @Tool getForecastWeather() : 기상청 단기예보 - 오늘/내일 시간대별 예보
 *
 * [ChatbotService와의 연연동 포인트]
 * - searchWebAndNews()가 이미지 검색 결과를 만들 때 본문에 삽입하는
 * "IMAGE_URLS: url1,url2,...\n[SYSTEM: ...]" 형식의 전용 마커 문자열은,
 * ChatbotService.parseReply()가 그대로 인식해서 텍스트 답변과 이미지 목록을
 * 분리해내는 "약속된 프로토콜"이다. 이 포맷 문자열을 바꾸면 parseReply()도
 * 함께 수정해야 한다.
 * - BoardImageService가 다루는 "/api/member/image/..." 같은 내부 회원/게시판
 * 이미지와 달리, 본 클래스가 다루는 이미지는 네이버 검색으로 얻어온 외부
 * https:// 이미지 링크라는 점이 다르다. (둘 다 최종적으로는 ChatbotService.
 * parseReply()를 거쳐 프론트의 동일한 이미지 렌더링 영역에 표시된다.)
 * - 생성자에 googleSearchKey/googleSearchCx(Google Custom Search 키/검색엔진ID)를
 * 주입받고 있지만, 현재 이미지 검색 실제 구현(searchWebAndNews 내부)은
 * 네이버 이미지 검색 API(openapi.naver.com)를 사용하고 있어 이 두 필드는
 * 아직 실제 호출부에서 사용되고 있지 않다(향후 Google Custom Search 연동 대비용).
 * ================================================================================
 */
@Slf4j
@Service
public class AIExternalSearchService {

    // WebClient: 네이버 뉴스/이미지 검색, 기상청 API 등 모든 외부 REST 호출에 공용으로 사용
    private final WebClient webClient;

    // 기상청 단기예보 API(공공데이터포털) 서비스키 - getWeatherInfo(), getForecastWeather()에서 사용
    private final String weatherApiKey;

    // 네이버 오픈API 인증 정보 - callNaverNews(), searchWebAndNews() 내부 이미지 검색에서 사용
    private final String naverClientId;
    private final String naverClientSecret;

    // Google Custom Search JSON API 인증 정보 (현재 미사용 - 향후 이미지 검색 대체/보강용으로 주입만 되어있음)
    private final String googleSearchKey;
    private final String googleSearchCx;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode EMPTY_NODE = MAPPER.createObjectNode();

    /**
     * @Value로 application.yml/properties에 설정된 외부 API 인증 정보를 주입받는 생성자.
     * (Lombok @RequiredArgsConstructor 대신 @Value 파라미터 바인딩을 위해 명시적으로 작성됨)
     */
    public AIExternalSearchService(
            WebClient.Builder webClientBuilder,
            @Value("${external.api.weather.key}") String weatherApiKey,
            @Value("${external.api.naver.client-id}") String naverClientId,
            @Value("${external.api.naver.client-secret}") String naverClientSecret,
            @Value("${external.api.google.search-key}") String googleSearchKey,
            @Value("${external.api.google.search-cx}") String googleSearchCx) {
        this.webClient = webClientBuilder.build();
        this.weatherApiKey = weatherApiKey;
        this.naverClientId = naverClientId;
        this.naverClientSecret = naverClientSecret;
        this.googleSearchKey = googleSearchKey;
        this.googleSearchCx = googleSearchCx;
    }

    // 네이버 뉴스 검색 API 호출 (display 기본값 10)
    private Mono<JsonNode> callNaverNews(String query, String sort) {
        return callNaverNews(query, sort, 10);
    }

    // ✅ display 개수를 지정할 수 있는 오버로드.
    //    오늘 날짜로 후처리 필터링을 해야 하는 경우(isTodayQuery), 필터링 후 남는 기사 수가
    //    줄어들 수 있으므로 후보 자체를 더 넉넉히 받아오기 위해 사용한다.
    private Mono<JsonNode> callNaverNews(String query, String sort, int display) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https").host("openapi.naver.com").path("/v1/search/news.json")
                        .queryParam("query", query)
                        .queryParam("display", display)
                        .queryParam("sort", sort)  // "date"(최신순) 또는 "sim"(정확도순)
                        .build())
                .header("X-Naver-Client-Id", naverClientId)
                .header("X-Naver-Client-Secret", naverClientSecret)
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                    response.bodyToMono(String.class).flatMap(body -> {
                        log.error("[Tool] 네이버 뉴스 API HTTP 에러: status={}, body={}", response.statusCode(), body);
                        return Mono.error(new RuntimeException("네이버 뉴스 API 에러: " + response.statusCode()));
                    })
                )
                .bodyToMono(JsonNode.class)
                .doOnError(e -> log.error("[Tool] 네이버 뉴스 API 호출 실패", e))
                .onErrorReturn(EMPTY_NODE);
    }

    // ✅ 신규: "오늘/내일/모레" 같은 상대 날짜 표현을 기사 발행일(pubDate) 기준으로 절대 날짜로 환산해 괄호 주석 추가
    //    LLM이 날짜 산술을 스스로 하지 않아도 되도록 코드가 미리 계산해서 텍스트에 박아 넣음
    private String annotateRelativeDates(String text, String pubDate) {
        try {
            ZonedDateTime articleDate = ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME);
            LocalDate base = articleDate.toLocalDate();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("M월 d일");

            String result = text;
            result = result.replace("모레", "모레(" + base.plusDays(2).format(fmt) + ")");
            result = result.replace("내일", "내일(" + base.plusDays(1).format(fmt) + ")");
            result = result.replace("오늘", "오늘(" + base.format(fmt) + ")");
            return result;
        } catch (Exception e) {
            return text; // 파싱 실패 시 원문 그대로 반환 (안전하게 무시)
        }
    }

    /**
     * 네이버 뉴스 API 응답(JSON)을 LLM이 읽기 쉬운 한 줄짜리 텍스트 스니펫 리스트로 변환.
     * - title/description에 포함된 <b> 하이라이트 태그 제거
     * - annotateRelativeDates()를 호출해 "오늘/내일/모레" 표현에 절대 날짜를 괄호로 병기
     * - 결과는 searchWebAndNews()에서 snippets 리스트에 누적되어 최종 답변 근거로 사용됨
     */
    // recentDaysFilter: -1이면 필터링 없음(기존 동작 그대로).
    //                   0 이상이면 isWithinRecentDays()로 걸러서 그 범위를 벗어나는 기사는 제외.
    private void parseNaverNews(JsonNode result, List<String> snippets, int limit, int recentDaysFilter) {
        if (!result.has("items")) return;
        JsonNode items = result.get("items");
        int added = 0;
        for (int i = 0; i < items.size() && added < limit; i++) {
            JsonNode item = items.get(i);
            String title = item.has("title") ? item.get("title").asText().replaceAll("<[^>]+>", "") : "";
            String desc  = item.has("description") ? item.get("description").asText().replaceAll("<[^>]+>", "") : "";
            String pubDate = item.has("pubDate") ? item.get("pubDate").asText() : "";

            if (title.isEmpty()) continue;
            if (recentDaysFilter >= 0 && !isWithinRecentDays(pubDate, recentDaysFilter)) continue;

            String rawText = title + ": " + desc;
            String annotated = pubDate.isEmpty() ? rawText : annotateRelativeDates(rawText, pubDate);
            snippets.add("[뉴스" + (pubDate.isEmpty() ? "" : " (" + pubDate + ")") + "] " + annotated);
            added++;
        }
    }

    // ✅ 신규: 기사 pubDate가 "오늘 - toleranceDays" ~ "오늘" 범위 안에 있는지 판정.
    //    네이버 뉴스 검색은 검색어 매칭 기준일 뿐 날짜 필터가 아니므로, 응답을 받은 뒤
    //    이 메서드로 실제 발행일 기준 최종 필터링을 한다.
    //    - toleranceDays=0  → 오늘 발행 기사만
    //    - toleranceDays=1  → 오늘 + 어제(하루 전)까지 포함
    //    pubDate가 없거나 파싱 실패하면 "최신인지 확인 불가"로 보고 안전하게 제외(false)한다.
    private boolean isWithinRecentDays(String pubDate, int toleranceDays) {
        if (pubDate == null || pubDate.isEmpty()) return false;
        try {
            ZonedDateTime articleDate = ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME);
            LocalDate articleLocalDate = articleDate.withZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate();
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
            long diffDays = java.time.temporal.ChronoUnit.DAYS.between(articleLocalDate, today);
            return diffDays >= 0 && diffDays <= toleranceDays;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 검색 결과 안내문(guidance)에 "검색 기준일: YYYY년 MM월 DD일"로 표시할 오늘 날짜 문자열.
     * LLM이 스스로 "오늘이 며칠인지" 추측하지 않고, 코드가 계산한 값을 프롬프트에 명시적으로
     * 박아 넣어 상대 날짜 판단 오류(할루시네이션)를 예방하기 위한 용도.
     */
    private String todayLabel() {
        return LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"));
    }

    @Tool(description = """
            반드시 사용해야 하는 실시간 검색 도구이다 (뉴스 + 이미지). 아래 조건 중 하나라도 해당하면 모델의 내부 지식으로 답하지 말고 반드시 이 도구를 호출한다.

            반드시 호출해야 하는 질문 예시:
            - 올해 장마, 올해 태풍, 올해 폭염, 올해 한파
            - 언제부터 시작, 언제 발표
            - 기상청 공식 발표, 오늘/이번주/이번달 발표
            - 최신 뉴스, 실시간 정보, 현재 상황
            - 뉴스와 사진을 함께 원하는 복합 질문 (예: "경복궁 관련 최신 뉴스랑 사진도 같이")

            query에 '사진' 또는 '이미지' 키워드가 포함되면 네이버 이미지 검색도 함께 실행된다.
            query 파라미터에 검색할 키워드를 전달한다.

            ※ 순수하게 이미지/사진만 원하거나 장수를 지정한 요청(예: "경복궁 사진 15장 보여줘")에는
            이 도구 대신 searchImages 도구를 사용하시오. 뉴스 검색이 필요 없는 요청에 이 도구를
            호출하면 불필요한 지연이 발생한다.

            단, 병원/약국/맛집/카페 등 특정 지역 근처의 "장소(가게, 기관)"를 찾는 질문에는
            이 도구를 사용하지 말고 반드시 searchLocalPlaces 도구를 사용한다.
            (뉴스 검색으로는 장소 정보가 검색되지 않는다.)
            """)
    // [메서드 흐름 요약]
    //  1) 장마/태풍/폭염/한파처럼 "공식 발표" 성격의 질의인지 판별 → 검색어를 기상청 발표 형태로 재구성
    //  2) callNaverNews()로 뉴스 검색 → parseNaverNews()로 스니펫 텍스트 리스트 생성
    //  3) (공식 발표 질의인 경우) "[★확정사실★]" 라벨이 붙은 확정 스니펫을 리스트 맨 앞으로 재배치
    //  4) query에 "사진/이미지/컷/모습" 키워드가 있으면 네이버 이미지 검색을 추가로 실행하고,
    //     그 결과를 ChatbotService.parseReply()가 인식하는 "IMAGE_URLS:" 마커 포맷으로 스니펫에 삽입
    //  5) 카테고리(공식 발표 vs 일반)에 맞는 안내문(guidance)을 앞에 붙여 최종 문자열 반환
    //     → 이 반환값이 그대로 LLM에게 Tool 실행 결과로 전달되어 답변 생성에 사용됨
    public String searchWebAndNews(String query) {
        log.info("[Tool] 검색: query='{}'", query);
        try {
            boolean isOfficialAnnouncement =
                    (query.contains("장마") || query.contains("태풍") || query.contains("폭염특보") || query.contains("한파특보"))
                    && (query.contains("시작") || query.contains("언제") || query.contains("발표") || query.contains("전망"));

            log.info("[Tool] 공식발표 판단: official={}", isOfficialAnnouncement);

            // 오늘 날짜 기준으로 뉴스 검색 필터링을 보강하기 위한 판단 변수 생성
            boolean isTodayQuery = query.contains("오늘") || query.contains("실시간") || query.contains("최신");
            String sortOrder = (isOfficialAnnouncement || isTodayQuery) ? "date" : "sim";

            String effectiveQuery;
            if (isOfficialAnnouncement) {
                String topic = query.contains("장마") ? "장마"
                             : query.contains("태풍") ? "태풍"
                             : query.contains("폭염특보") ? "폭염"
                             : "한파";
                String location = query.contains("서울") ? "서울"
                                 : query.contains("부산") ? "부산"
                                 : query.contains("제주") ? "제주"
                                 : "";
                effectiveQuery = (location + " " + topic + " 시작 기상청 발표").trim();
            } else if (isTodayQuery) {
                // ✅ 수정: "7월 24일" 같은 날짜 문자열을 검색어에 AND 키워드로 끼워넣지 않는다.
                //    네이버 뉴스 검색은 매칭 기준이지 날짜 필터가 아니라서, 날짜 문자열을 넣으면
                //    그 문구가 본문에 그대로 있는 극소수(대부분 과거 사건을 언급하는) 기사만
                //    걸리는 문제가 있었다. "오늘/최신/실시간/중요/주요/이슈" 같은 의미 없는
                //    수식어만 제거해 핵심 키워드만 남기고, 최신성 보장은 sort=date +
                //    아래 pubDate 기반 후처리 필터링(isWithinRecentDays)으로 넘긴다.
                String cleanedQuery = query
                        .replaceAll("오늘의|오늘|실시간|최신|중요한?|주요|이슈", "")
                        .replaceAll("\\s+", " ")
                        .trim();
                effectiveQuery = cleanedQuery.isEmpty() ? "뉴스 속보" : cleanedQuery;
            } else {
                effectiveQuery = query;
            }

            log.info("[Tool] 최종 검색어: '{}', 정렬방식: '{}'", effectiveQuery, sortOrder);

            // ✅ isTodayQuery인 경우, pubDate 후처리 필터링으로 걸러지고 남는 기사가 줄어들 수 있으므로
            //    후보군 자체를 30건으로 넉넉히 받아온다(그 외에는 기존과 동일하게 10건).
            JsonNode result = callNaverNews(effectiveQuery, sortOrder, isTodayQuery ? 30 : 10).block();
            if (result == null) return "검색 결과가 비어있습니다.";

            List<String> snippets = new ArrayList<>();
            // ✅ isTodayQuery면 "오늘 + 하루 전(어제)"까지만 통과(toleranceDays=1), 그 외에는 필터링 없음(-1)
            parseNaverNews(result, snippets, 5, isTodayQuery ? 1 : -1);

            // 공식 발표 질의일 경우, "공식 발표/들어섰다고/시작됐다고/시작일" 등 확정 사실 키워드가 담긴
            // 항목을 최신순과 무관하게 최우선으로 재배치
            if (isOfficialAnnouncement) {
                List<String> factSnippets = new ArrayList<>();
                List<String> otherSnippets = new ArrayList<>();
                for (String s : snippets) {
                    if (s.contains("공식 발표") || s.contains("들어섰다고") || s.contains("시작됐다고") || s.contains("시작일")) {
                        factSnippets.add("[★확정사실★] " + s);
                    } else {
                        otherSnippets.add(s);
                    }
                }
                snippets = new ArrayList<>(factSnippets);
                snippets.addAll(otherSnippets);
            }

            log.info("[Tool] 네이버 뉴스 원본 파싱 결과:\n{}", String.join("\n", snippets));

            // 이미지 검색 (뉴스 결과 유무와 무관하게 실행) ──
            boolean needsImage = effectiveQuery.contains("사진") || effectiveQuery.contains("이미지")
                    || effectiveQuery.contains("컷") || effectiveQuery.contains("모습");
            if (needsImage) {
                try {
                    // 뉴스+이미지 복합 질문에서는 이미지는 참고용 소수(6장)만 곁들인다.
                    // 장수를 지정한 순수 이미지 요청은 searchImages() 도구가 별도로 처리한다.
                    List<String> imageUrls = fetchNaverImages(effectiveQuery, 6);

                    if (!imageUrls.isEmpty()) {
                        StringBuilder imgBuilder = new StringBuilder();

                        // [최종 마감]: ChatbotService의 parseReply()가 100% 인식하는 전용 구분자 포맷을 정확히 제공합니다.
                        // [수정] 기존 "Do not display, reformat, or output..." 문구를 LLM이
                        // "이 줄 자체를 답변에서 빼도 된다"로 오해하면서 마커가 통째로 유실되는
                        // 문제가 있었음(특히 USER 권한 세션에서 재현). "삭제/생략 금지, 반드시 그대로
                        // 포함"을 명시적으로 못박아 이 오해를 없앰.
                        imgBuilder.append("\n\nIMAGE_URLS: ").append(String.join(",", imageUrls)).append("\n");
                        imgBuilder.append("[SYSTEM: 위 IMAGE_URLS 줄은 반드시 당신의 답변 맨 끝에 토씨 하나 " +
                                "틀리지 않고 그대로 포함시키세요. 이 줄을 삭제하거나 생략하면 절대 안 됩니다. " +
                                "다만 이 줄을 마크다운 이미지 태그로 바꾸거나 별도의 이미지 목록으로 다시 " +
                                "나열하지는 마세요.]\n");

                        snippets.add(imgBuilder.toString());
                        log.info("[Tool] 네이버 이미지 {}건 IMAGE_URLS 포맷으로 바인딩 완료", imageUrls.size());
                    } else {
                        snippets.add("\nIMAGE_SEARCH_RESULT: 이미지 검색 결과 없음");
                    }
                } catch (Exception e) {
                    log.warn("[Tool] 네이버 이미지 검색 실패: {}", e.getMessage());
                    snippets.add("\nIMAGE_SEARCH_RESULT: 이미지 검색 실패 (원인: " + e.getMessage() + ")");
                }
            }

            if (snippets.isEmpty()) {
                if (isOfficialAnnouncement) return "공식 발표 검색 결과 없음 - 아직 발표 전이거나 관련 뉴스 없음";
                // ✅ isTodayQuery인데 필터링 후 하나도 안 남았다면, 검색 자체가 실패한 게 아니라
                //    "오늘/어제자로 발행된 관련 기사가 없다"는 뜻이므로 LLM이 옛날 기사를
                //    끌어와 답하지 않도록 그 사실을 명확히 알려준다.
                return isTodayQuery
                        ? "검색 결과 없음 - 오늘/어제 발행된 관련 기사를 찾지 못했습니다. 오래된 과거 기사를 근거로 " +
                          "답변하지 말고, 최근 관련 뉴스를 찾지 못했다고 사용자에게 명확히 안내하시오."
                        : "검색 결과 없음";
            }

            String guidance = isOfficialAnnouncement
                ? String.format("""
                    === SYSTEM ===
                    검색 결과를 한 줄씩 확인하시오.
                    '[★확정사실★]' 라벨이 붙은 줄이 하나라도 있으면, 그 줄의 내용을 근거로 반드시 답변을 작성하시오.
                    이 경우 "아직 발표가 없다"는 답변은 금지된다.
                    '[★확정사실★]' 라벨이 하나도 없을 때만, 발표된 내용이 아직 없다는 취지로 답변하시오.
                    검색 결과에 있는 날짜와 수치를 그대로 인용하시오. 괄호로 표기된 절대 날짜(예: 내일(7월 3일))가 있으면 그 날짜를 그대로 사용하시오.
                    평년 통계나 일반적인 시기(6월 하순~7월 초 등)로 답을 대체하지 마시오.
                    검색 기준일: %s
                    === SEARCH RESULT ===
                    """, todayLabel())
                : String.format("""
                    === SYSTEM ===
                    아래 검색 결과를 우선 사용하여 답변하시오.
                    날짜가 표기된 결과를 우선 신뢰하시오. 괄호로 표기된 절대 날짜가 있으면 그 날짜를 그대로 사용하시오.
                    검색 기준일: %s
                    === SEARCH RESULT ===
                    """, todayLabel());

            return guidance + String.join("\n", snippets);

        } catch (Exception e) {
            log.error("[Tool] 검색 오류", e);
            return "검색 실패: " + e.getMessage();
        }
    }

    // ✅ 신규: 네이버 이미지 검색 공통 헬퍼 - searchImages()와 searchWebAndNews() 양쪽에서 재사용.
    //    display 개수를 파라미터로 받아 호출부(순수 이미지 검색 vs 뉴스 곁들임용 소수 검색)마다
    //    원하는 장수를 자유롭게 지정할 수 있도록 한다.
    private List<String> fetchNaverImages(String query, int display) throws Exception {
        // 1. 한글 검색어를 안전하게 URL 인코딩 (이중 인코딩 방지)
        String encodedQuery = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8.toString());

        // 2. & 기호와 파라미터가 잘리지 않도록 완전한 Raw URL 문자열을 직접 조립
        String naverImageUrl = String.format(
            "https://openapi.naver.com/v1/search/image?query=%s&display=%d&start=1&sort=sim",
            encodedQuery, display
        );

        // [디버깅용 로그] 네이버로 전송되기 직전의 완전한 주소를 확인하기 위함
        log.info("[DEBUG] 최종 전송할 네이버 이미지 검색 URL: {}", naverImageUrl);

        // 3. uriBuilder를 쓰지 않고, 문자열 주소 그대로 WebClient 호출
        JsonNode imageResult = webClient.get()
                .uri(new java.net.URI(naverImageUrl)) // 완성된 URI 객체를 직접 주입
                .header("X-Naver-Client-Id", naverClientId)
                .header("X-Naver-Client-Secret", naverClientSecret)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        // 4. 결과 파싱
        List<String> imageUrls = new ArrayList<>();
        if (imageResult != null && imageResult.has("items")) {
            JsonNode items = imageResult.get("items");
            for (int i = 0; i < Math.min(items.size(), display); i++) {
                JsonNode item = items.get(i);
                if (item.has("link")) {
                    String imgUrl = item.get("link").asText();

                    // HTTP 주소인 경우 엑박 방지를 위해 HTTPS로 강제 변경
                    if (imgUrl != null && imgUrl.startsWith("http://")) {
                        imgUrl = imgUrl.replace("http://", "https://");
                    }
                    imageUrls.add(imgUrl);
                }
            }
        }
        return imageUrls;
    }

    @Tool(description = """
            이미지/사진 전용 검색 도구. 뉴스 검색 없이 이미지만 바로 조회하므로 searchWebAndNews보다 빠르다.
            사용자가 순수하게 사진/이미지만 요청하거나(예: "경복궁 사진 보여줘"), 구체적인 장수를
            지정한 경우(예: "사진 15장", "10개 보여줘") 반드시 이 도구를 사용한다.
            뉴스와 이미지를 함께 원하는 복합 질문이 아니라면 searchWebAndNews 대신 이 도구를 우선 사용하시오.

            count 파라미터에 사용자가 요청한 이미지 개수를 전달한다 (예: "15장" → 15).
            언급이 없으면 null로 전달하며, 이 경우 기본값 6장이 적용된다. 최대 30장까지 허용된다.
            """)
    // ✅ 신규: [메서드 흐름 요약]
    //  1) count가 없거나 0 이하이면 기본값 6, 30을 넘으면 30으로 상한 적용(네이버 이미지 검색 API 최대치인
    //     100보다는 낮게 잡아 응답 지연/과다 노출을 방지)
    //  2) fetchNaverImages()로 이미지만 바로 조회 (뉴스 API 호출 없음)
    //  3) searchWebAndNews()와 동일한 "IMAGE_URLS:" 마커 포맷으로 반환하여
    //     ChatbotService.parseReply()가 그대로 인식하도록 프로토콜을 통일
    public String searchImages(String query, Integer count) {
        int display = (count == null || count <= 0) ? 6 : Math.min(count, 30);
        log.info("[Tool] 이미지 전용 검색: query='{}', count={}", query, display);

        try {
            List<String> imageUrls = fetchNaverImages(query, display);
            if (imageUrls.isEmpty()) {
                return "이미지 검색 결과 없음";
            }

            StringBuilder imgBuilder = new StringBuilder();
            imgBuilder.append("\n\nIMAGE_URLS: ").append(String.join(",", imageUrls)).append("\n");
            imgBuilder.append("[SYSTEM: 위 IMAGE_URLS 줄은 반드시 당신의 답변 맨 끝에 토씨 하나 " +
                    "틀리지 않고 그대로 포함시키세요. 이 줄을 삭제하거나 생략하면 절대 안 됩니다. " +
                    "다만 이 줄을 마크다운 이미지 태그로 바꾸거나 별도의 이미지 목록으로 다시 " +
                    "나열하지는 마세요.]\n");
            return imgBuilder.toString();

        } catch (Exception e) {
            log.warn("[Tool] 이미지 전용 검색 실패: {}", e.getMessage());
            return "이미지 검색 실패 (원인: " + e.getMessage() + ")";
        }
    }

    // ✅ 신규(2026-07-15): 네이버 지역 검색 API 호출
    //    뉴스 API(news.json)는 언론사 기사만 검색하므로 "구로디지털단지역 근처 이비인후과"처럼
    //    장소(POI)를 찾는 질의에는 결과가 항상 비어 나오는 문제가 있었음 → 지역 검색 API(local.json)로 해결
    //    (callNaverNews()와 동일한 네이버 오픈API 인증키를 그대로 사용하므로 별도 설정 추가 불필요)
    private Mono<JsonNode> callNaverLocal(String query) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https").host("openapi.naver.com").path("/v1/search/local.json")
                        .queryParam("query", query)
                        .queryParam("display", 5)      // 네이버 지역 검색 API의 display 최대값은 5
                        .queryParam("start", 1)
                        .queryParam("sort", "random")  // 정확도순 (네이버 지역 검색 기본 정렬)
                        .build())
                .header("X-Naver-Client-Id", naverClientId)
                .header("X-Naver-Client-Secret", naverClientSecret)
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                    response.bodyToMono(String.class).flatMap(body -> {
                        log.error("[Tool] 네이버 지역 API HTTP 에러: status={}, body={}", response.statusCode(), body);
                        return Mono.error(new RuntimeException("네이버 지역 API 에러: " + response.statusCode()));
                    })
                )
                .bodyToMono(JsonNode.class)
                .doOnError(e -> log.error("[Tool] 네이버 지역 API 호출 실패", e))
                .onErrorReturn(EMPTY_NODE);
    }

    @Tool(description = """
            네이버 지역(장소) 검색 도구이다. 특정 지역/역/동네 근처의 병원, 약국, 이비인후과,
            맛집, 카페, 미용실, 상점, 기관 등 "장소"를 찾는 질문이면 반드시 이 도구를 호출한다.
            (예: 구로디지털단지역 근처 이비인후과 추천, 강남역 맛집, 서울역 근처 약국)
            뉴스 검색 도구(searchWebAndNews)로는 장소 정보가 검색되지 않으므로 대체 사용을 금지한다.
            query 파라미터에는 '지역명 + 업종' 형태의 간결한 키워드를 전달한다. 예: '구로디지털단지역 이비인후과'
            """)
    // ✅ 신규(2026-07-15) [메서드 흐름 요약]
    //  1) callNaverLocal()로 네이버 지역 검색 API 호출 (최대 5건)
    //  2) title의 <b> 하이라이트 태그 제거 후 상호명/분류/주소/링크를 한 줄 스니펫으로 조립
    //  3) "목록에 있는 장소만 사용, 진료시간·후기 등 없는 정보 추측 금지" guidance를 앞에 붙여 반환
    //     → 지역 검색 결과에는 진료시간/후기가 없으므로 LLM의 할루시네이션을 원천 차단
    public String searchLocalPlaces(String query) {
        log.info("[Tool] 지역 검색: query='{}'", query);
        try {
            JsonNode result = callNaverLocal(query).block();
            if (result == null || !result.has("items") || result.get("items").size() == 0) {
                return "지역 검색 결과 없음 - 검색어를 '지역명 + 업종'(예: 구로디지털단지역 이비인후과) 형태로 "
                     + "바꿔 이 도구를 1회만 다시 호출하고, 그래도 결과가 없으면 사용자에게 재시도 여부를 묻지 말고 "
                     + "결과가 없다고 명확히 답변하시오.";
            }

            List<String> snippets = new ArrayList<>();
            JsonNode items = result.get("items");
            for (int i = 0; i < items.size(); i++) {
                JsonNode item = items.get(i);
                String name = item.has("title") ? item.get("title").asText().replaceAll("<[^>]+>", "") : "";
                if (name.isEmpty()) continue;

                String category    = item.has("category")    ? item.get("category").asText()    : "";
                String roadAddress = item.has("roadAddress") ? item.get("roadAddress").asText() : "";
                String address     = item.has("address")     ? item.get("address").asText()     : "";
                String link        = item.has("link")        ? item.get("link").asText()        : "";

                StringBuilder sb = new StringBuilder();
                sb.append("[장소").append(i + 1).append("] ").append(name);
                if (!category.isEmpty()) sb.append(" | 분류: ").append(category);
                String addr = !roadAddress.isEmpty() ? roadAddress : address;  // 도로명주소 우선, 없으면 지번주소
                if (!addr.isEmpty())     sb.append(" | 주소: ").append(addr);
                if (!link.isEmpty())     sb.append(" | 링크: ").append(link);
                snippets.add(sb.toString());
            }

            if (snippets.isEmpty()) return "지역 검색 결과 없음";

            log.info("[Tool] 네이버 지역 검색 파싱 결과:\n{}", String.join("\n", snippets));

            String guidance = String.format("""
                    === SYSTEM ===
                    아래는 네이버 지역 검색으로 조회한 실제 장소 목록이다.
                    이 목록에 있는 상호명과 주소만 그대로 사용하여 답변하시오.
                    목록에 없는 장소를 지어내거나, 진료시간·후기·평점 등 목록에 없는 정보를 추측해서 답하지 마시오.
                    진료시간과 후기는 이 결과에 포함되지 않으므로, 상호명으로 네이버 지도에서
                    확인하도록 안내하는 문구를 답변 끝에 덧붙이시오.
                    검색 기준일: %s
                    === SEARCH RESULT ===
                    """, todayLabel());

            return guidance + String.join("\n", snippets);

        } catch (Exception e) {
            log.error("[Tool] 지역 검색 오류", e);
            return "지역 검색 실패: " + e.getMessage();
        }
    }

    @Tool(description = """
            기상청 공식 실시간 날씨 조회 도구 (한국 날씨 정확).
            현재 기온, 습도, 날씨 상태, 강수 여부가 필요한 경우 사용한다.
            location 파라미터에 도시명을 한국어 또는 영어로 전달한다. 예: 서울, Seoul
            """)
    // [메서드 흐름 요약] 기상청 초단기실황(getUltraSrtNcst) API로 "현재 시각" 기준 실측 날씨 조회.
    //  1) GRID_MAP에서 지역명을 기상청 격자좌표(nx, ny)로 변환 (매핑에 없으면 앞 2글자로 재시도, 그래도 없으면 서울 기본값)
    //  2) 초단기실황은 매 시각 40분 이후 발표되므로, 현재 분(minute)이 40 미만이면 baseTime을 한 시간 전으로 보정
    //  3) 응답 항목(category)별 관측값을 Map으로 모아 기온(T1H)/습도(REH)/강수형태(PTY)/강수량(RN1)/풍속(WSD) 추출
    //  4) 강수형태 코드(PTY)를 한글 설명으로 변환 후 한 줄 요약 문자열로 반환
    public String getWeatherInfo(String location) {
        log.info("[Tool] 날씨 API 호출: location='{}'", location);
        try {
            int[] grid = GRID_MAP.getOrDefault(location,
                          GRID_MAP.getOrDefault(
                              location.length() >= 2 ? location.substring(0, 2) : location,
                              new int[]{60, 127}));
            int nx = grid[0], ny = grid[1];

            LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
            LocalTime now   = LocalTime.now(ZoneId.of("Asia/Seoul"));

            int baseHour = now.getMinute() >= 40 ? now.getHour() : now.getHour() - 1;
            if (baseHour < 0) baseHour = 23;
            String baseDate = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String baseTime = String.format("%02d00", baseHour);

            JsonNode response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https").host("apis.data.go.kr")
                            .path("/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst")
                            .queryParam("serviceKey", weatherApiKey)
                            .queryParam("numOfRows", "10")
                            .queryParam("pageNo", "1")
                            .queryParam("dataType", "JSON")
                            .queryParam("base_date", baseDate)
                            .queryParam("base_time", baseTime)
                            .queryParam("nx", nx)
                            .queryParam("ny", ny)
                            .build())
                    .retrieve().bodyToMono(JsonNode.class).block();

            if (response == null) return "날씨 데이터 없음";

            JsonNode items = response.path("response").path("body")
                                     .path("items").path("item");
            if (!items.isArray() || items.size() == 0) return "날씨 데이터 없음";

            Map<String, String> data = new HashMap<>();
            for (JsonNode item : items) {
                data.put(item.get("category").asText(), item.get("obsrValue").asText());
            }

            String t1h = data.getOrDefault("T1H", "-");
            String reh = data.getOrDefault("REH", "-");
            String pty = data.getOrDefault("PTY", "0");
            String rn1 = data.getOrDefault("RN1", "0");
            String wsd = data.getOrDefault("WSD", "-");

            String ptyDesc = switch (pty) {
                case "1" -> "비";
                case "2" -> "비/눈";
                case "3" -> "눈";
                case "4" -> "소나기";
                default  -> "맑음";
            };

            return String.format(
                "[실시간 날씨 - 기상청] %s → %s, 기온: %s°C, 습도: %s%%, 풍속: %sm/s, 강수량: %smm",
                location, ptyDesc, t1h, reh, wsd, rn1);

        } catch (Exception e) {
            log.error("[Tool] 날씨 오류", e);
            return "날씨 조회 실패: " + e.getMessage();
        }
    }

    // ── 기상청 격자 좌표 매핑 (주요 도시) ──────────────────────────────
    private static final Map<String, int[]> GRID_MAP = new HashMap<>() {{
        put("Seoul",    new int[]{60, 127});
        put("서울",     new int[]{60, 127});
        put("Busan",    new int[]{98, 76});
        put("부산",     new int[]{98, 76});
        put("Incheon",  new int[]{55, 124});
        put("인천",     new int[]{55, 124});
        put("Daegu",    new int[]{89, 90});
        put("대구",     new int[]{89, 90});
        put("Daejeon",  new int[]{67, 100});
        put("대전",     new int[]{67, 100});
        put("Gwangju",  new int[]{58, 74});
        put("광주",     new int[]{58, 74});
        put("Suwon",    new int[]{60, 121});
        put("수원",     new int[]{60, 121});
    }};

    @Tool(description = """
            기상청 공식 단기예보 조회 도구 (한국 날씨 정확).
            시간대별 날씨, 강수확률, 기온 예보가 필요한 경우 사용한다.
            location 파라미터에 도시명을 한국어 또는 영어로 전달한다. 예: 서울, Seoul
            """)
    // [메서드 흐름 요약] 기상청 단기예보(getVilageFcst) API로 "오늘/내일" 3시간 단위 예보 조회.
    //  1) GRID_MAP으로 격자좌표 변환은 getWeatherInfo()와 동일한 방식
    //  2) 단기예보는 하루 8회(02,05,08,11,14,17,20,23시) 발표되므로, 현재 시각 기준으로
    //     "발표 후 1시간이 지난" 가장 최근 baseHour를 역순 탐색으로 결정
    //  3) 응답의 모든 (날짜+시각) 조합을 timeMap에 모은 뒤, 오늘/내일 데이터만 최대 16개 슬롯까지 추려
    //     기온(TMP)/강수확률(POP)/강수형태(PTY)/하늘상태(SKY)/습도(REH)를 한 줄씩 이어붙여 반환
    public String getForecastWeather(String location) {
        log.info("[Tool] 날씨 예보 API 호출: location='{}'", location);
        try {
            int[] grid = GRID_MAP.getOrDefault(location,
                          GRID_MAP.getOrDefault(
                              location.substring(0, Math.min(2, location.length())),
                              new int[]{60, 127}));
            int nx = grid[0], ny = grid[1];

            LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
            LocalTime now   = LocalTime.now(ZoneId.of("Asia/Seoul"));
            int[] baseTimes = {2, 5, 8, 11, 14, 17, 20, 23};
            int baseHour = 2;
            for (int t : baseTimes) {
                if (now.getHour() >= t + 1) baseHour = t;
            }
            String baseDate = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String baseTime = String.format("%02d00", baseHour);

            JsonNode response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https").host("apis.data.go.kr")
                            .path("/1360000/VilageFcstInfoService_2.0/getVilageFcst")
                            .queryParam("serviceKey", weatherApiKey)
                            .queryParam("numOfRows", "200")
                            .queryParam("pageNo", "1")
                            .queryParam("dataType", "JSON")
                            .queryParam("base_date", baseDate)
                            .queryParam("base_time", baseTime)
                            .queryParam("nx", nx)
                            .queryParam("ny", ny)
                            .build())
                    .retrieve().bodyToMono(JsonNode.class).block();

            if (response == null) return "날씨 예보 데이터 없음";

            JsonNode items = response.path("response").path("body")
                                     .path("items").path("item");
            if (!items.isArray() || items.size() == 0) return "날씨 예보 데이터 없음";

            Map<String, Map<String, String>> timeMap = new java.util.TreeMap<>();
            for (JsonNode item : items) {
                String fcstDate = item.get("fcstDate").asText();
                String fcstTime = item.get("fcstTime").asText();
                String category = item.get("category").asText();
                String value    = item.get("fcstValue").asText();
                String key      = fcstDate + fcstTime;
                timeMap.computeIfAbsent(key, k -> new HashMap<>()).put(category, value);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[").append(location).append(" 날씨 예보 (기상청)]");

            String todayStr    = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String tomorrowStr = today.plusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            int count = 0;

            for (Map.Entry<String, Map<String, String>> entry : timeMap.entrySet()) {
                String key  = entry.getKey();
                String date = key.substring(0, 8);
                String time = key.substring(8);

                if (!date.equals(todayStr) && !date.equals(tomorrowStr)) continue;
                if (count >= 16) break;

                Map<String, String> data = entry.getValue();
                String tmp  = data.getOrDefault("TMP",  "-");
                String pop  = data.getOrDefault("POP",  "0");
                String pty  = data.getOrDefault("PTY",  "0");
                String sky  = data.getOrDefault("SKY",  "1");
                String reh  = data.getOrDefault("REH",  "-");

                String skyDesc = switch (sky) {
                    case "1" -> "맑음";
                    case "3" -> "구름많음";
                    case "4" -> "흐림";
                    default  -> "맑음";
                };
                String ptyDesc = switch (pty) {
                    case "1" -> "비";
                    case "2" -> "비/눈";
                    case "3" -> "눈";
                    case "4" -> "소나기";
                    default  -> "";
                };
                String weather = ptyDesc.isEmpty() ? skyDesc : ptyDesc;
                String label   = date.equals(todayStr) ? "오늘" : "내일";
                String timeStr = time.substring(0, 2) + ":" + time.substring(2);

                sb.append(String.format(
                    "%s %s - %s, 기온: %s°C, 습도: %s%%, 강수확률: %s%%",
                    label, timeStr, weather, tmp, reh, pop
                ));
                count++;
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("[Tool] 날씨 예보 오류", e);
            return "날씨 예보 조회 실패: " + e.getMessage();
        }
    }
}