package com.board.service.chatbot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import com.board.dto.chatbot.ChatbotMessageDTO;
import com.board.dto.chatbot.ChatbotRequestDTO;
import com.board.dto.chatbot.ChatbotRequestDTO.AttachmentDTO;
import com.board.dto.chatbot.ChatbotResponseDTO;
import com.board.entity.MemberEntity;
import com.board.entity.repository.MemberRepository;
import com.board.service.agent.SecretaryTools;
import com.board.session.ChatbotSession;
import com.board.session.ChatbotSessionManager;
import com.board.util.FileTextExtractor;
import com.board.util.ImageUtils;
import reactor.core.publisher.Flux;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ================================================================================
 * Spring AI 기반의 지능형 챗봇 서비스 클래스
 * ================================================================================
 * 사용자의 권한(MASTER/SECRETARY/일반)과 질문 의도(RouterAgent 분류)를 분석해서
 * 아래 4가지 방식 중 하나(혹은 조합)로 답변을 만들어냅니다.
 *
 *   1) RAG 검색        : Vector DB(pgvector)에서 관련 문서를 찾아 답변에 활용 (ATTENDANCE_RAG)
 *   2) DB Tool Calling  : LLM이 필요하면 직접 SQL Tool을 호출해서 DB를 조회 (ATTENDANCE_DB, BOARD_DB)
 *   3) 외부 연동 Tool    : Gmail/Calendar/Slack/웹검색 Tool 호출 (SECRETARY, WEB_SEARCH)
 *   4) 일반 대화        : 별도 Tool 없이 LLM 자체 지식으로 답변 (GENERAL_CHAT)
 *
 * [이번 리팩토링에서 고친 부분 요약]
 *  (A) streamChat()에도 이미지(프로필/게시판) 선조회 로직을 추가함
 *      → 기존에는 chat()에만 있어서 스트리밍 모드에서는 이미지가 전혀 안 나갔음
 *  (B) ATTENDANCE_RAG 요청인데 MASTER가 아니라서 Tool이 하나도 안 붙는 경우,
 *      "모른다고 답하지 마라"는 공통 지시와 충돌해서 LLM이 헛소리(할루시네이션)를
 *      만들어낼 위험이 있었음 → 권한 부족 시 정직하게 안내하도록 시스템 프롬프트를 분리함
 *  (C) 첨부파일 처리 시, 지원하지 않는 파일 형식은 "AI에게는 조용히 누락"되면서
 *      "대화 이력에는 이미지로 잘못 기록"되는 불일치가 있었음 → 처리 로직을 하나로 통합
 *  (D) chat()과 streamChat()에 90% 동일한 라우팅/시스템프롬프트 코드가 중복되어 있었음
 *      → buildRoutedPrompt() 공통 메서드로 추출해서 유지보수 지점을 1곳으로 축소
 *  (E) resolveImageRequest() 내부에서 "재요청"과 "게시글 번호(seqno) 조회"가 동시에
 *      매칭되면 두 결과가 섞여버리는 문제 → 먼저 매칭된 조건에서 즉시 반환하도록 수정
 *  (F) streamChat()에 에러 처리가 없어서 예외 발생 시 사용자에게 아무 안내 없이
 *      스트림이 그냥 끊기는 문제 → onErrorResume으로 에러 메시지를 흘려보내도록 수정
 * ================================================================================
 *
 * [전체 연동 구조 - 4개 클래스가 어떻게 협력하는가]
 *
 *   ChatbotService (본 클래스, 오케스트레이터/컨트롤타워)
 *     ├─ RouterAgent           : 사용자 메시지를 5~6가지 카테고리 중 하나로 분류
 *     │                          (ATTENDANCE_RAG / ATTENDANCE_DB / BOARD_DB / SECRETARY / WEB_SEARCH / GENERAL_CHAT)
 *     │                          이 분류 결과에 따라 applyCategoryRouting()이 아래 Tool 중
 *     │                          무엇을 ChatClient에 장착할지 결정한다.
 *     │
 *     ├─ BoardQueryService     : (BOARD_DB 카테고리) @Tool 로 등록되어 LLM이 자연어를
 *     │                          SQL로 변환해 게시판/회원 Oracle DB를 조회하도록 위임하는 대상.
 *     │                          ChatbotService는 이 클래스를 "Tool 보유자"로만 알고 있고,
 *     │                          내부 SQL 생성/보안 검증 로직에는 관여하지 않는다.
 *     │
 *     ├─ BoardImageService     : (이미지 선조회 전용) LLM에게 맡기지 않고 ChatbotService가
 *     │                          resolveImageRequest() 안에서 "직접" 먼저 호출하는 순수 조회
 *     │                          헬퍼 서비스. "프로필 사진 보여줘" 같은 요청은 Tool Calling
 *     │                          없이도 정확도/속도를 위해 여기서 곧바로 URL을 확보한다.
 *     │                          (BoardQueryService와 달리 @Tool이 아니라 일반 스프링 빈으로만 주입됨)
 *     │
 *     ├─ AIExternalSearchService : (WEB_SEARCH/SECRETARY 카테고리, 그리고 GENERAL_CHAT에서도
 *     │                          ChatClient의 defaultTools로 전역 등록되어 있어 상시 사용 가능)
 *     │                          뉴스 검색(Naver), 이미지 검색(Naver), 지역/장소 검색(Naver),
 *     │                          실시간/예보 날씨(기상청)
 *     │                          Tool을 제공. searchWebAndNews()가 반환하는 "IMAGE_URLS:" 마커는
 *     │                          ChatbotService.parseReply()가 그대로 파싱해서 화면에 이미지로
 *     │                          렌더링한다 (BoardImageService가 만드는 "/api/member/..." 내부
 *     │                          경로와는 별개로, 외부 https 이미지 링크를 다루는 경로).
 *     │
 *     └─ SecretaryTools        : (SECRETARY 카테고리, MASTER+SECRETARY 권한 필요)
 *                                Gmail/Calendar/Slack Tool. 본 파일에서는 상세 구조를 다루지
 *                                않지만 applyCategoryRouting()에서 동일한 패턴으로 등록된다.
 *
 *   [한 요청의 처리 흐름 요약 (chat/streamChat 공통)]
 *     1) resolveImageRequest() - BoardImageService를 이용해 "이미지 요청"인지 먼저 판별/선조회
 *     2) routerAgent.classify() - 질문 의도를 카테고리로 분류
 *     3) applyCategoryRouting() - 카테고리에 맞는 Tool(BoardQueryService/SecretaryTools 등) 또는
 *        Vector DB advisor(RAG)를 ChatClient 요청에 동적으로 장착
 *     4) buildFinalSystemPrompt() - 권한(master/secretary)과 카테고리에 맞는 지시문 조립
 *     5) LLM 호출 → 필요 시 LLM이 장착된 Tool(BoardQueryService, AIExternalSearchService 등)을
 *        스스로 호출 → 결과를 반영한 최종 답변 생성
 *     6) parseReply() - 답변 속 "IMAGE_URLS:" 마커(AIExternalSearchService가 심어놓은 것) 또는
 *        평문 이미지 링크를 추출해 텍스트와 이미지 목록을 분리
 *     7) 세션(Redis)에 이력 저장 후 최종 응답 반환
 * ================================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatbotService {

    private final ChatClient chatClient;
    private final ChatbotSessionManager sessionManager;
    private final BoardQueryService boardQueryService;
    private final BoardImageService boardImageService;
    private final AIExternalSearchService aiExternalSearchService;
    private final SecretaryTools secretaryTools;
    private final MemberRepository memberRepository;
    private final VectorStore vectorStore;
    private final RouterAgent routerAgent;
    private final TrainingQueryService trainingQueryService;

    // "나", "내"처럼 화자 본인을 가리키는 대명사 모음. 이름 추출 시 오탐 방지용으로 사용
    private static final Set<String> PRONOUNS =
            Set.of("나", "내", "저", "제", "우리", "본인", "자신", "나의", "저의", "내가", "제가");

    // LLM에게 그대로 넘길 수 있는(이미지로 인식시킬 수 있는) MimeType 목록
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");

    // 텍스트 추출이 가능한 문서류 MimeType 목록 (내용을 뽑아서 프롬프트에 텍스트로 삽입)
    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-powerpoint",
            "text/plain", "text/csv", "text/markdown");

    // ============================================================================
    // 1. 자연어 패턴 판별용 헬퍼 메서드들 (정규식 대신 명시적 문자열 검사로 구현)
    // ============================================================================

    /** "다시 보여줘", "again" 등 재요청 표현이 포함되어 있는지 판별 */
    private boolean isRepeatRequest(String message) {
        if (message == null) return false;
        String clean = message.replaceAll("\\s+", "").toLowerCase();
        return clean.contains("다시")
            || clean.contains("또보여")
            || clean.contains("한번더")
            || clean.contains("again")
            || clean.contains("재요청");
    }

    /** "전체 회원 프로필 사진 보여줘" 같은 전체 회원 이미지 일괄 요청인지 판별 */
    private boolean isAllMembersProfileRequest(String message) {
        if (message == null) return false;
        String clean = message.replaceAll("\\s+", "").toLowerCase();

        if (!clean.contains("회원")) return false;

        boolean hasAllKeyword = clean.contains("전체") || clean.contains("모든")
                             || clean.contains("모두") || clean.contains("전원");
        if (!hasAllKeyword) return false;

        return clean.contains("프로필") || clean.contains("프로파일") || clean.contains("profile")
            || clean.contains("사진") || clean.contains("이미지") || clean.contains("photo")
            || clean.contains("image") || clean.contains("pic");
    }

    /**
     * 메시지 안에서 "홍길동이", "이순신의"처럼 한글 이름(3~6자) 뒤에 조사가 붙은
     * 패턴을 찾아 조사를 뗀 순수 이름만 추출
     */
    private String extractNameWithPostposition(String message) {
        if (message == null) return null;

        String[] tokens = message.split("\\s+");
        for (String token : tokens) {
            if (token.length() >= 3 && token.length() <= 6) {
                if (token.endsWith("가") || token.endsWith("이") || token.endsWith("의")
                 || token.endsWith("은") || token.endsWith("는")) {
                    return token.substring(0, token.length() - 1);
                }
            }
        }
        return null;
    }

    // ============================================================================
    // 2. 권한(Role) 체크 헬퍼
    // ============================================================================

    /** 현재 로그인한 사용자가 SECRETARY(비서) 권한을 가지고 있는지 확인 */
    private boolean isSecretary() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        String email = auth.getName();
        Optional<MemberEntity> member = memberRepository.findById(email);
        return member.map(m -> "Y".equals(m.getSecretary())).orElse(false);
    }

    /** 현재 로그인한 사용자가 MASTER(최고 관리자) 권한을 가지고 있는지 확인 */
    private boolean isMaster() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("MASTER"));
    }

    // ============================================================================
    // 3. 시스템 프롬프트 조립 (역할별/카테고리별)
    // ============================================================================

    /** MASTER 전용 안내: 전체 회원 개인정보 및 훈련생 통계 조회 권한 */
    private String buildMasterSystemPrompt() {
        return "현재 사용자는 MASTER 관리자입니다.\n" +
            "모든 회원의 개인정보(전화번호, 주소 포함)를 조회할 수 있습니다.\n" +
            "회원 개인정보 조회 시 boardQueryService Tool을 호출하세요.\n" +
            "훈련생 수료/출석 정보 조회 시 trainingQueryService Tool을 호출하세요.\n";
    }

    /** SECRETARY 전용 안내: Gmail/Calendar 조작 시 확인 절차 및 중복 출력 방지 규칙 */
    private String buildSecretarySystemPrompt() {
        return "당신은 개인 비서 AI입니다.\n" +
            "Gmail과 Google Calendar에 접근할 수 있습니다.\n" +
            "오늘 날짜: " + LocalDate.now() + "\n\n" +
            "[CRITICAL - 반드시 지켜야 할 규칙]\n" +
            "1. 확인 메시지는 절대로 단 1번만 작성하세요. 같은 내용을 절대 반복하지 마세요.\n\n" +
            "2. 메일 삭제, 일정 삭제, 일정 수정 요청 시 확인 메시지를 1번만 보내세요.\n" +
            "    사용자가 '네', '응', '맞아', '확인', '진행해줘' 등 긍정 답변 시에만 실행하세요.\n" +
            "    부정 답변이면 취소하세요.\n\n" +
            "3. 일정 생성, 메일 발송, 답장, 전달 요청 시 내용을 1번만 보여주고 확인을 받으세요.\n" +
            "    확인 후에만 실행하세요.\n\n" +
            "4. 조회(메일 목록, 일정 확인)는 확인 없이 바로 실행하세요.\n\n" +
            "5. 이미지 검색 요청 시 검색 Tool을 호출하여 이미지를 직접 찾아 보여주세요.\n\n" +
            "6. 자료에 없는 외부 정보(회사 매출, 최신 뉴스, 주가, 기업 정보 등)는\n" +
            "    반드시 웹 검색 Tool을 호출하여 최신 정보를 찾아 답변하세요.\n" +
            "    절대로 '맥락에 없습니다', '확인할 수 없습니다'라고 답하지 말고 웹 검색을 먼저 시도하세요.\n" +
            "    웹 검색 결과와 이력서 맥락을 함께 활용하여 답변하세요.\n\n" +
            "[절대 금지]\n" +
            "- 같은 문장이나 내용을 두 번 이상 출력하는 것은 절대 금지입니다.\n" +
            "- 응답 내에 동일한 내용이 반복되면 안 됩니다.\n";
    }

    /**
     * 공통 기본 시스템 프롬프트 (보안 규칙 + 데이터 조회 우선순위 + Tool 사용 규칙)
     *
     * [수정 (B) - 1차 수정] 처음에는 "이번 요청에 붙은 Tool이 하나도 없으면(hasAnyTool=false)
     * 무조건 권한 부족 안내로 전환"하도록 만들었는데, 이 조건이 너무 넓었습니다.
     * GENERAL_CHAT처럼 원래 switch문에서는 Tool을 안 붙이지만 ChatClient에 전역으로
     * 기본 등록된 Tool(예: AIExternalSearchService)이 실제로는 동작하는 카테고리에도
     * "너는 Tool이 없다"는 문구가 잘못 들어가면서, 비서 프롬프트의
     * "이미지 검색 시 Tool을 호출해서 보여줘라"는 지시와 충돌 → 사진이 아예 안 나오는
     * 회귀 버그가 발생했습니다.
     *
     * [수정 (B) - 2차 수정] 그래서 "권한 부족 안내"는 실제로 문제가 됐던
     * 딱 한 가지 케이스, 즉 "ATTENDANCE_RAG인데 MASTER가 아니라서 Vector DB를 아예
     * 조회할 수 없는 경우"에만 적용하도록 범위를 좁혔습니다. 그 외 모든 카테고리는
     * 원래대로 "Tool을 적극적으로 활용하라"는 공통 지시를 그대로 받습니다.
     */
    private String buildBaseSystemPrompt(
            String currentEmail, String currentUsername,
            TaskCategory category, boolean master) {

        // ATTENDANCE_RAG인데 MASTER가 아니면 → Vector DB advisor 자체가 붙지 않으므로
        // 이 경우에만 "권한 부족, 지어내지 마라" 안내로 전환
        boolean attendanceRagDenied = category == TaskCategory.ATTENDANCE_RAG && !master;
        StringBuilder sb = new StringBuilder();
        sb.append("현재 로그인 사용자 이메일: ").append(currentEmail).append("\n")
          .append("현재 로그인 사용자 이름: ").append(currentUsername).append("\n")
          .append("사용자가 '나', '내', '저', '제' 등 본인을 지칭할 때는 위 이메일로 조회하세요.\n\n")
          .append("[데이터 조회 우선순위 - 반드시 준수]\n")
          .append("1. 훈련 과정 수료생 수, 명단, 출석률 등 수치 질문은 ")
          .append("trainingQueryService Tool(getTrainingSchema, executeTrainingSql)을 호출하세요.\n")
          .append("2. 이력서, 훈련 제도 안내 등 문서 내용은 등록된 자료에서 찾으세요.\n")
          .append("3. 회원/게시판/게시물/댓글/통계는 boardQueryService Tool을 호출하세요.\n")
          .append("4. training_trainee, jpa_training 같은 테이블은 존재하지 않습니다. 절대 조회하지 마세요.\n")
          .append("5. training_course, training_student 테이블은 pgvector DB에 있습니다.\n")
          // [2026-07-15 신규] 지역(장소) 검색 라우팅: 뉴스 API로 병원/맛집 등 장소를 찾다가
          // 결과가 비어 되묻기만 반복하던 문제 대응 → AIExternalSearchService.searchLocalPlaces로 유도
          .append("6. 특정 지역/역/동네 근처의 병원, 약국, 맛집, 카페 등 장소를 찾는 질문은 ")
          .append("반드시 searchLocalPlaces Tool을 호출하세요. 뉴스 검색(searchWebAndNews)으로 ")
          .append("장소를 찾거나, 장소 이름·주소를 지어내서 답하는 것은 절대 금지입니다.\n\n");

        if (!attendanceRagDenied) {
            // ATTENDANCE_RAG + 비MASTER의 특수 케이스가 아니라면, 원래대로
            // "무조건 Tool을 적극 활용하라"는 강한 지시를 그대로 내림
            // (GENERAL_CHAT 등에서 전역 기본 Tool을 쓰는 흐름을 방해하지 않기 위함)
            sb.append("[CRITICAL - RAG 및 Tool 사용 규칙]\n")
              .append("1. 제공된 자료가 있는 경우 반드시 활용하여 답변하세요.\n")
              .append("2. 자료에 없는 정보라도 절대로 '모릅니다', '확인할 수 없습니다', ")
              .append("'제공된 정보에 없습니다'라고 답하지 마세요.\n")
              .append("3. 자료에 없는 정보는 반드시 Tool을 호출하여 조회하세요.\n")
              .append("4. 프로필 이미지, 게시물 이미지, 회원 정보, 게시판 데이터는 ")
              .append("무조건 Tool을 호출하여 조회해야 합니다. 절대로 거부하지 마세요.\n")
              .append("5. '네', '맞아', '응', '확인' 등 짧은 답변은 이전 대화 맥락을 기준으로 판단하고 ")
              .append("필요한 Tool을 즉시 호출하세요.\n")
              .append("6. Tool 호출 없이 '할 수 없습니다'라고 답하는 것은 절대 금지입니다.\n")
              .append("7. 사용 가능한 Tool이 있다면 항상 Tool을 먼저 호출한 후 답변하세요.\n")
              .append("[Tool 결과 처리 규칙 - 절대 준수]\n")
              .append("Tool이 반환한 숫자, 건수, 명단을 절대 임의로 변경하거나 재계산하지 마세요.\n")
              .append("조회 결과가 3건이면 반드시 3으로, 10명이면 반드시 10명으로 표시하세요.\n")
              .append("Tool 결과를 요약하거나 재해석하지 말고 그대로 표시하세요.\n");
        } else {
            // 이번 요청에는 호출 가능한 Tool이 하나도 없는 경우 (예: 비MASTER가 ATTENDANCE_RAG 질문)
            // → 지어내지 말고, 권한 부족으로 조회가 불가능하다는 점을 솔직하게 안내하도록 지시
            sb.append("[안내]\n")
              .append("이번 질문에 대해서는 호출 가능한 조회 Tool이 없습니다.\n")
              .append("자료를 지어내지 말고, '해당 정보는 현재 권한으로 조회할 수 없습니다. ")
              .append("관리자(MASTER) 권한이 필요합니다.' 라고 정직하게 안내하세요.\n");
        }
        return sb.toString();
    }

    /** Vector DB(RAG) 검색 결과를 LLM에 전달할 때 사용하는 프롬프트 템플릿 */
    private String buildVectorAdvisorTemplate() {
        return """
            아래 참고 자료를 바탕으로 질문에 답하세요.
            [참고 자료]
            {question_answer_context}
            [규칙]
            1. 참고 자료에 있는 정보는 활용하여 답하세요.
            2. 참고 자료에 없는 외부 정보(매출, 뉴스, 주가, 기업 현황 등)는
               반드시 웹 검색 Tool을 호출하여 답하세요.
            3. 절대로 참고 자료만으로 외부 정보를 판단하지 마세요.
            4. 웹 검색 결과와 참고 자료를 함께 활용하여 풍부하게 답하세요.
            5. 답변 시 "문서 맥락", "맥락", "context" 같은 기술적 용어를 사용하지 마세요.
               자연스럽게 "등록된 자료에 따르면", "확인된 내용으로는" 등으로 표현하세요.
            질문: {query}
            """;
    }

    // ============================================================================
    // 4. [수정 (D)] 라우팅 공통 로직 - chat()과 streamChat()이 함께 사용
    // ============================================================================

    /**
     * RouterAgent 분류 결과에 따라 Vector DB advisor 또는 Tool을 prompt spec에 장착합니다.
     *
     * chat()과 streamChat()에 완전히 동일하게 들어가던 switch문을 한 곳으로 모아서,
     * 카테고리별 라우팅 규칙이 바뀔 때 이 메서드 하나만 고치면 되도록 정리했습니다.
     *
     * [참고] 이전에는 여기서 "Tool이 붙었는지(hasAnyTool)"까지 함께 계산해서
     * 시스템 프롬프트 분기에 썼는데, GENERAL_CHAT처럼 이 switch와 무관하게
     * ChatClient에 전역으로 기본 등록된 Tool이 동작하는 카테고리까지 "Tool 없음"으로
     * 잘못 판단하는 부작용이 있었습니다 (강아지 사진 검색이 안 되던 회귀 버그).
     * 그래서 지금은 라우팅과 "권한 부족 여부 판단"을 분리했고, 권한 부족 판단은
     * buildBaseSystemPrompt()에서 category/master 값만으로 직접 계산합니다.
     */
    private ChatClient.ChatClientRequestSpec applyCategoryRouting(
            ChatClient.ChatClientRequestSpec prompt,
            TaskCategory category,
            boolean master,
            boolean secretary,
            String logPrefix) {

        switch (category) {
            case ATTENDANCE_RAG -> {
                log.info("[{}] ATTENDANCE_RAG → Vector DB 검색", logPrefix);
                if (master) {
                    prompt = prompt.advisors(QuestionAnswerAdvisor.builder(vectorStore)
                            .searchRequest(SearchRequest.builder().topK(20).build())
                            .promptTemplate(PromptTemplate.builder()
                                .template(buildVectorAdvisorTemplate())
                                .build())
                            .build());
                }
                // master가 아니면 advisor를 붙이지 않음 → buildBaseSystemPrompt()가
                // 이 경우만 콕 집어 "권한 부족 안내" 문구를 넣어줌
            }
            case ATTENDANCE_DB -> {
                log.info("[{}] ATTENDANCE_DB → PostgreSQL 훈련생 DB 조회", logPrefix);
                prompt = prompt.tools(trainingQueryService);
            }
            case BOARD_DB -> {
                log.info("[{}] BOARD_DB → Oracle Tool Calling", logPrefix);
                prompt = prompt.tools(boardQueryService);
            }
            case SECRETARY -> {
                log.info("[{}] SECRETARY → Gmail/Calendar/Slack Tool", logPrefix);
                if (secretary) prompt = prompt.tools(secretaryTools);
            }
            case WEB_SEARCH -> {
                log.info("[{}] WEB_SEARCH → 웹검색 (defaultTools로 등록됨)", logPrefix);
            }
            case GENERAL_CHAT -> log.info("[{}] GENERAL_CHAT → 일반 대화", logPrefix);
        }

        return prompt;
    }

    /**
     * 역할(master/secretary) 조합 + 카테고리에 맞는 최종 시스템 프롬프트를 조립합니다.
     * "권한 부족 안내" 문구는 ATTENDANCE_RAG + 비MASTER 케이스에서만 buildBaseSystemPrompt
     * 내부적으로 자동 적용되고, 그 외에는 원래와 동일하게 동작합니다.
     */
    private String buildFinalSystemPrompt(
            String currentEmail, String currentUsername,
            TaskCategory category, boolean master, boolean secretary) {

        String base = buildBaseSystemPrompt(currentEmail, currentUsername, category, master);
        if (master && secretary) return base + buildMasterSystemPrompt() + buildSecretarySystemPrompt();
        if (master) return base + buildMasterSystemPrompt();
        if (secretary) return base + buildSecretarySystemPrompt();
        return base;
    }

    // ============================================================================
    // 5. 챗봇 API 진입점 (단발성 동기 호출 / 스트리밍 호출)
    // ============================================================================

    /** 챗봇 단발성 동기 요청 핸들러 (한 번에 완성된 답변을 반환) */
    public ChatbotResponseDTO chat(ChatbotRequestDTO request) {
        ChatbotSession session = sessionManager.getOrCreate(request.getSessionId());
        try {
            List<Message> historyMessages = buildHistoryMessages(session);
            List<AttachmentDTO> attachments = request.getAttachments();
            String message = request.getMessage();
            UserMessage userMessage = buildUserMessage(message, attachments);

            // 1. 이미지(프로필/게시판) 선조회 - 질문 자체가 "사진 보여줘" 류라면 여기서 바로 URL을 확보
            List<String> preImageUrls = resolveImageRequest(message, session);
            log.info("preImageUrls: {}", preImageUrls);

            if (!preImageUrls.isEmpty()) {
                // 이미지는 이미 찾았으니 LLM은 추가 조회 없이 안내 문구만 붙이도록 지시
                userMessage = new UserMessage(message +
                    "\n[시스템: 이미지가 이미 조회되어 화면에 표시됩니다. " +
                    "추가 검색이나 DB 조회 없이 '이미지를 찾아 표시했습니다.' 라고만 답하세요.]");
            }

            // 2. 로그인 사용자 정보 로드
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String currentEmail = auth != null ? auth.getName() : null;
            String currentUsername = currentEmail != null
                    ? memberRepository.findById(currentEmail).map(MemberEntity::getUsername).orElse("")
                    : "";

            boolean master = isMaster();
            boolean secretary = isSecretary();
            log.info("isMaster: {}, isSecretary: {}", master, secretary);

            // 3. RouterAgent 의도 분류
            TaskCategory category = routerAgent.classify(message);
            log.info("[chat] TaskCategory: {}", category);

            // 4. 카테고리별 Tool/advisor 라우팅 (chat/streamChat 공통 로직)
            ChatClient.ChatClientRequestSpec prompt = chatClient.prompt()
                    .messages(historyMessages)
                    .messages(userMessage);
            prompt = applyCategoryRouting(prompt, category, master, secretary, "chat");

            // 5. 최종 시스템 프롬프트 부착 (ATTENDANCE_RAG+비MASTER일 때만 안내 문구로 전환 - 수정 B)
            String systemPrompt = buildFinalSystemPrompt(currentEmail, currentUsername, category, master, secretary);
            ChatClient.ChatClientRequestSpec finalPrompt = prompt.system(systemPrompt);

            // 6. AI 엔진 호출
            String reply = finalPrompt.call().content();
            log.info("AI reply 원문: '{}'", reply);

            // 7. 답변 속 이미지 링크 파싱
            ParsedReply parsed = parseReply(reply);
            List<String> allImageUrls = new ArrayList<>(preImageUrls);
            allImageUrls.addAll(parsed.imageUrls());
            log.info("allImageUrls: {}", allImageUrls);

            // 8. 세션(대화 이력) 갱신 및 Redis 저장
            session.addUserMessage(buildHistoryContent(message, attachments));
            session.addAssistantMessage(reply, allImageUrls.isEmpty() ? null : allImageUrls);
            sessionManager.saveToRedis(session);

            // 9. 본문에 남아있는 이미지 URL 텍스트 제거 (프론트에서 이미지는 별도 필드로 렌더링하므로)
            String cleanText = removeUrlsFromText(parsed.text(), allImageUrls);

            return ChatbotResponseDTO.success(
                    session.getSessionId(),
                    cleanText,
                    session.getHistory(),
                    allImageUrls.isEmpty() ? null : allImageUrls);

        } catch (Exception e) {
            log.error("Chat error - sessionId: {}", session.getSessionId(), e);
            return ChatbotResponseDTO.error(session.getSessionId(), "오류가 발생했습니다: " + e.getMessage());
        }
    }

    /** 답변 본문 안에서, 이미 이미지 필드로 분리해 낸 URL 문자열들을 제거해 텍스트를 깔끔하게 정리 */
    private String removeUrlsFromText(String text, List<String> imageUrls) {
        if (text == null || imageUrls == null || imageUrls.isEmpty()) return text;
        String result = text;
        for (String url : imageUrls) {
            result = result.replace(url, "").trim();
        }
        result = result.replaceAll("(?m)^\\s*$\\n?", "").trim();
        return result;
    }

    /**
     * 실시간 스트리밍 챗 API
     *
     * [수정 (A)] 기존에는 chat()에만 있던 "이미지 선조회" 로직이 없어서, 스트리밍 모드에서는
     * "내 프로필 사진 보여줘" 같은 요청을 해도 이미지가 전혀 나가지 않았습니다.
     * 여기서는 chat()과 동일하게 resolveImageRequest()를 먼저 호출하고, 확보한 이미지 URL을
     * Flux 스트림의 맨 앞부분에 특수 마커 형태로 흘려보내서 프론트가 파싱해 쓸 수 있게 합니다.
     *
     * [수정 (F)] 예외 발생 시 로그만 남기던 것을, 사용자에게도 에러 메시지가 스트림으로
     * 전달되도록 onErrorResume을 추가했습니다.
     */
    public Flux<String> streamChat(String sessionId, String userMessage, List<AttachmentDTO> attachments) {
        ChatbotSession session = sessionManager.getOrCreate(sessionId);
        List<Message> historyMessages = buildHistoryMessages(session);
        StringBuilder fullReply = new StringBuilder();
        UserMessage builtMessage = buildUserMessage(userMessage, attachments);

        // 1. 이미지 선조회 (chat()과 동일한 로직 재사용)
        List<String> preImageUrls = resolveImageRequest(userMessage, session);
        log.info("[stream] preImageUrls: {}", preImageUrls);

        if (!preImageUrls.isEmpty()) {
            builtMessage = new UserMessage(userMessage +
                "\n[시스템: 이미지가 이미 조회되어 화면에 표시됩니다. " +
                "추가 검색이나 DB 조회 없이 '이미지를 찾아 표시했습니다.' 라고만 답하세요.]");
        }

        // 2. 로그인 사용자 정보 로드
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String currentEmail = auth != null ? auth.getName() : null;
        String currentUsername = currentEmail != null
                ? memberRepository.findById(currentEmail).map(MemberEntity::getUsername).orElse("")
                : "";

        boolean master = isMaster();
        boolean secretary = isSecretary();

        // 3. RouterAgent 의도 분류
        TaskCategory category = routerAgent.classify(userMessage);
        log.info("[stream] TaskCategory: {}", category);

        // 4. 카테고리별 Tool/advisor 라우팅 (chat()과 동일한 공통 메서드 사용 - 수정 D)
        ChatClient.ChatClientRequestSpec prompt = chatClient.prompt()
                .messages(historyMessages)
                .messages(builtMessage);
        prompt = applyCategoryRouting(prompt, category, master, secretary, "stream");

        // 5. 최종 시스템 프롬프트 부착
        String systemPrompt = buildFinalSystemPrompt(currentEmail, currentUsername, category, master, secretary);
        ChatClient.ChatClientRequestSpec finalPrompt = prompt.system(systemPrompt);

        // 6. 이미지 URL을 먼저 흘려보낼 Flux (있을 때만) + 실제 LLM 스트리밍 응답을 이어붙임
        //    프론트에서는 "IMAGE_URLS:" 로 시작하는 첫 청크를 보고 이미지 목록을 파싱하면 됨
        Flux<String> imageMarkerFlux = preImageUrls.isEmpty()
                ? Flux.empty()
                : Flux.just("IMAGE_URLS:" + String.join(",", preImageUrls) + "\n");

        Flux<String> contentFlux = finalPrompt.stream()
                .content()
                .doOnNext(fullReply::append)
                .doOnComplete(() -> {
                    session.addUserMessage(buildHistoryContent(userMessage, attachments));
                    session.addAssistantMessage(fullReply.toString(), preImageUrls.isEmpty() ? null : preImageUrls);
                    sessionManager.saveToRedis(session);
                    log.debug("Stream completed - sessionId: {}", session.getSessionId());
                })
                .onErrorResume(e -> {
                    // 수정 (F): 에러가 나도 프론트가 알 수 있도록 안내 문구를 스트림에 흘려보냄
                    log.error("Stream error - sessionId: {}", session.getSessionId(), e);
                    return Flux.just("\n[오류가 발생했습니다. 잠시 후 다시 시도해 주세요: " + e.getMessage() + "]");
                });

        return Flux.concat(imageMarkerFlux, contentFlux);
    }

    /** 특정 세션 제거 */
    public void clearSession(String sessionId) {
        sessionManager.removeSession(sessionId);
        log.info("Session removed: {}", sessionId);
    }

    /** 대화 기록 리스트 조회 */
    public List<ChatbotMessageDTO> getHistory(String sessionId) {
        return sessionManager.getOrCreate(sessionId).getHistory();
    }

    // ============================================================================
    // 6. 이미지 선조회 로직 - "OO 프로필 사진 보여줘" 류의 요청을 가로채 직접 처리
    // ============================================================================

    /**
     * 사용자 메시지가 이미지 조회성 요청인지 판별하고, 맞다면 URL 목록을 직접 채워서 반환합니다.
     * (LLM에게 맡기지 않고 서비스 코드에서 먼저 처리 → 정확도와 속도를 확보)
     *
     * [수정 (E)] 기존에는 "재요청" 블록과 "게시글 번호(seqno)" 블록이 모두 return 없이
     * 순차 실행되어서, 두 조건이 동시에 맞으면 서로 다른 이미지들이 한 리스트에 섞여버릴
     * 수 있었습니다. 재요청 블록에서 결과를 찾으면 그 즉시 반환하도록 수정해서
     * "한 번의 요청 = 한 종류의 의도"가 되도록 정리했습니다.
     */
    private List<String> resolveImageRequest(String message, ChatbotSession session) {
        List<String> imageUrls = new ArrayList<>();
        if (message == null) return imageUrls;

        // (1) "전체 회원 프로필 사진" 일괄 요청
        if (isAllMembersProfileRequest(message)) {
            List<String> urls = boardImageService.getAllMemberProfileImageUrls();
            imageUrls.addAll(urls);
            log.info("전체 회원 프로필 이미지 조회: count={}", urls.size());
            return imageUrls;
        }

        // (2) 개별 프로필 요청 ("내 프로필", "OO 프로필" 등)
        if (boardImageService.isProfileImageRequest(message)) {
            if (boardImageService.isSelfProfileRequest(message)) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                String currentEmail = auth != null ? auth.getName() : null;
                if (currentEmail != null) {
                    List<String> urls = boardImageService.getMemberProfileImageUrl(currentEmail);
                    imageUrls.addAll(urls);
                    if (!urls.isEmpty()) session.setLastProfileTarget("self:" + currentEmail);
                    log.info("내 프로필 이미지 조회: email={}, urls={}", currentEmail, urls);
                }
            } else {
                String name = boardImageService.extractMemberName(message);
                if ((name == null || PRONOUNS.contains(name)) && session.getLastProfileTarget() != null) {
                    String last = session.getLastProfileTarget();
                    if (last.startsWith("self:")) {
                        imageUrls.addAll(boardImageService.getMemberProfileImageUrl(last.substring(5)));
                        return imageUrls;
                    }
                    name = last;
                }
                if (name != null && !PRONOUNS.contains(name)) {
                    List<String> urls = boardImageService.getMemberProfileImageUrl(name);
                    imageUrls.addAll(urls);
                    if (!urls.isEmpty()) session.setLastProfileTarget(name);
                    log.info("프로필 이미지 직접 조회: name={}, urls={}", name, urls);
                }
            }
            return imageUrls;
        }

        // (3) "내가 등록한 게시물 이미지 전부" 요청
        if (boardImageService.isMyBoardImagesRequest(message)) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String currentEmail = auth != null ? auth.getName() : null;
            if (currentEmail != null) {
                List<String> urls = boardImageService.getMyBoardImageUrls(currentEmail);
                imageUrls.addAll(urls);
                log.info("내 게시물 이미지 전체 조회: email={}, count={}", currentEmail, urls.size());
                return imageUrls;
            }
        }

        // (4) "OO이 등록한 게시물 이미지" 처럼 제3자 게시판 이미지 요청
        boolean hasImageKeyword = message.contains("이미지") || message.contains("사진");
        boolean hasBoardKeyword = message.contains("게시판") || message.contains("게시물") || message.contains("등록");
        boolean hasPronoun = PRONOUNS.stream().anyMatch(message::contains);

        if (hasImageKeyword && hasBoardKeyword && !hasPronoun) {
            String name = extractNameWithPostposition(message);
            if (name != null && !PRONOUNS.contains(name)) {
                List<String> emails = boardImageService.findEmailsByName(name);
                for (String email : emails) {
                    imageUrls.addAll(boardImageService.getMyBoardImageUrls(email));
                }
                if (!imageUrls.isEmpty()) {
                    log.info("타인 게시물 이미지 조회: name={}, count={}", name, imageUrls.size());
                    return imageUrls;
                }
            }
        }

        // (5) 재요청("다시 보여줘") 감지 → 직전 조회 대상 재사용
        //     [수정 E] 결과를 찾았다면 여기서 바로 반환 (아래 seqno 로직과 섞이지 않도록)
        if (isRepeatRequest(message) && session.getLastProfileTarget() != null) {
            String last = session.getLastProfileTarget();
            String target = last.startsWith("self:") ? last.substring(5) : last;
            List<String> urls = boardImageService.getMemberProfileImageUrl(target);
            if (!urls.isEmpty()) {
                log.info("반복 요청 감지 → 마지막 조회 대상 재사용: '{}'", last);
                return urls;
            }
        }

        // (6) 게시글 고유 번호(SeqNo)를 직접 언급한 이미지 조회
        //     (재요청 조건과 매칭되지 않았을 때만 이 로직까지 도달)
        Long seqno = boardImageService.extractBoardSeqno(message);
        if (seqno != null) {
            List<String> urls = boardImageService.getBoardImageUrls(seqno);
            imageUrls.addAll(urls);
            log.info("게시물 이미지 직접 조회: seqno={}, urls={}", seqno, urls);
        }

        return imageUrls;
    }

    // ============================================================================
    // 7. 답변 텍스트 안에 섞여 있는 이미지 URL 파싱
    // ============================================================================

    /** LLM 답변 본문(text) + 그 안에서 뽑아낸 이미지 URL 목록(imageUrls)을 함께 담는 결과 객체 */
    private record ParsedReply(String text, List<String> imageUrls) {}

    /**
     * LLM 답변 안에 포함된 이미지 자원(외부 URL 또는 내부 API 경로)을 찾아 분리합니다.
     *  1) "IMAGE_URLS: a,b,c" 형식의 전용 구분자 라인을 먼저 확인
     *  2) 본문을 단어 단위로 쪼개서 http(s) 이미지 확장자 링크나 내부 프로필 이미지 경로를 스캔
     *  3) 이미지 URL만 남은 잔여 줄(순수 링크 줄)은 최종 텍스트에서 제거
     */
    private ParsedReply parseReply(String reply) {
        if (reply == null) return new ParsedReply("", new ArrayList<>());

        List<String> imageUrls = new ArrayList<>();
        String text = reply;

        // 1. "IMAGE_URLS:" 구분자 라인 추출
        if (text.contains("IMAGE_URLS:")) {
            int start = text.indexOf("IMAGE_URLS:");
            int end = text.indexOf("\n", start);
            String urlLine = end > 0 ? text.substring(start + 11, end) : text.substring(start + 11);

            Arrays.stream(urlLine.split(","))
                    .map(String::trim)
                    .filter(u -> !u.isBlank())
                    .forEach(u -> {
                        // [수정] http(s)로 시작하는 절대 URL(AIExternalSearchService가 만드는
                        // 외부 네이버 이미지 링크)까지 무조건 "/api"를 붙여버리는 바람에
                        // "/apihttps://..." 형태의 깨진 URL이 만들어지던 문제 수정.
                        // BoardImageService가 만드는 내부 상대경로일 때만 "/api"를 붙인다.
                        boolean isAbsoluteUrl = u.startsWith("http://") || u.startsWith("https://");
                        String url = (isAbsoluteUrl || u.startsWith("/api")) ? u : "/api" + u;
                        imageUrls.add(url);
                    });
            text = text.substring(0, start).trim();
        }

        // 2. 단어 단위 스캔으로 이미지 URL 탐지
        String[] words = text.split("[\\s()\\[\\]!]+");
        for (String word : words) {
            String cleanedWord = word.trim();
            if (cleanedWord.isBlank()) continue;

            if (cleanedWord.startsWith("http://") || cleanedWord.startsWith("https://")) {
                String lower = cleanedWord.toLowerCase();
                if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png")
                 || lower.contains(".gif") || lower.contains(".webp")) {
                    if (!imageUrls.contains(cleanedWord)) imageUrls.add(cleanedWord);
                }
            } else if (cleanedWord.startsWith("/api/member/image/")
                    || cleanedWord.startsWith("/member/image/")
                    || cleanedWord.startsWith("/api/member/viewProfile/")
                    || cleanedWord.startsWith("/member/viewProfile/")) {

                String finalUrl = cleanedWord.startsWith("/api") ? cleanedWord : "/api" + cleanedWord;
                if (!imageUrls.contains(finalUrl)) imageUrls.add(finalUrl);
            }
        }

        // 3. 순수 URL만 남은 잔여 줄 제거 (텍스트 가독성 정리)
        text = Arrays.stream(text.split("\n"))
                .map(String::trim)
                .filter(line -> !line.startsWith("http"))
                .filter(line -> !line.startsWith("(http"))
                .filter(line -> !line.startsWith("- http"))
                .filter(line -> !line.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"))
                .trim();

        return new ParsedReply(text, imageUrls);
    }

    // ============================================================================
    // 8. [수정 (C)] 첨부파일 처리 - 하나의 공통 로직으로 통합
    // ============================================================================

    /** 첨부파일 1개를 분석한 결과. 종류에 따라 media 또는 extractedText 중 하나만 채워짐 */
    private record AttachmentProcessed(
            AttachmentKind kind,
            Media media,            // 이미지인 경우에만 값 존재
            String extractedText,   // 문서인 경우에만 값 존재 (텍스트 추출 결과)
            String historyLabel     // 대화 이력에 남길 표시용 라벨 (모든 케이스에 항상 존재)
    ) {}

    private enum AttachmentKind { IMAGE, DOCUMENT, UNSUPPORTED }

    /**
     * 첨부파일 하나를 실제로 디코딩/처리하는 공통 메서드.
     *
     * [수정 C] 기존에는 buildUserMessage()와 buildHistoryContent()가 각각 따로 Base64 디코딩과
     * mime 분기 로직을 중복 구현하고 있었고, 그 둘의 분기 조건이 미묘하게 달라서
     * "지원하지 않는 파일 형식"이 들어왔을 때 LLM에게는 조용히 씹히는데 대화 이력에는
     * "[첨부 이미지: ...]"라고 잘못 기록되는 불일치가 있었습니다.
     * 이제는 이 메서드 하나에서 판별부터 라벨링까지 전부 처리하고, 두 호출부는 결과만 사용합니다.
     */
    private AttachmentProcessed processAttachment(AttachmentDTO att) {
        String mime = att.getMimeType();
        String name = att.getName();

        if (IMAGE_TYPES.contains(mime)) {
            try {
                String base64Data = att.getBase64Data();
                if (base64Data != null && base64Data.contains(",")) base64Data = base64Data.split(",")[1];
                byte[] data = Base64.getDecoder().decode(base64Data);
                data = ImageUtils.resizeIfNeeded(data, mime);
                Media media = Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType(mime))
                        .data(data).build();
                return new AttachmentProcessed(AttachmentKind.IMAGE, media, null, "[첨부 이미지: " + name + "]");
            } catch (Exception e) {
                log.warn("이미지 첨부파일 처리 실패: {}", name, e);
                return new AttachmentProcessed(AttachmentKind.UNSUPPORTED, null, null,
                        "[첨부 이미지 처리 실패: " + name + "]");
            }
        }

        if (DOCUMENT_TYPES.contains(mime)) {
            try {
                String base64Data = att.getBase64Data();
                if (base64Data != null && base64Data.contains(",")) base64Data = base64Data.split(",")[1];
                byte[] data = Base64.getDecoder().decode(base64Data);
                String extracted = FileTextExtractor.extract(data, mime, name);
                return new AttachmentProcessed(AttachmentKind.DOCUMENT, null, extracted,
                        "[첨부 파일: " + name + "]");
            } catch (Exception e) {
                log.warn("문서 첨부파일 처리 실패: {}", name, e);
                return new AttachmentProcessed(AttachmentKind.UNSUPPORTED, null, null,
                        "[첨부 파일 처리 실패: " + name + "]");
            }
        }

        // 이미지도 문서도 아닌 지원 대상 외 형식
        // → LLM에게도 "이런 파일이 첨부됐지만 처리할 수 없다"는 사실을 알려서
        //   혼자 조용히 누락되던 기존 문제를 없앰
        log.warn("지원하지 않는 첨부파일 형식: name={}, mime={}", name, mime);
        return new AttachmentProcessed(AttachmentKind.UNSUPPORTED, null, null,
                "[지원하지 않는 형식의 첨부파일: " + name + " (" + mime + ")]");
    }

    /** 첨부파일(이미지/문서)을 LLM에 넘길 UserMessage(텍스트+미디어)로 조립 */
    private UserMessage buildUserMessage(String message, List<AttachmentDTO> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new UserMessage(message != null ? message : "");
        }

        List<Media> mediaList = new ArrayList<>();
        StringBuilder textBuilder = new StringBuilder(message != null ? message : "");

        for (AttachmentDTO att : attachments) {
            AttachmentProcessed processed = processAttachment(att);
            switch (processed.kind()) {
                case IMAGE -> mediaList.add(processed.media());
                case DOCUMENT -> textBuilder.append("\n\n[첨부 파일: ").append(att.getName()).append("]\n")
                        .append(processed.extractedText());
                case UNSUPPORTED -> textBuilder.append("\n").append(processed.historyLabel());
            }
        }

        return mediaList.isEmpty()
                ? new UserMessage(textBuilder.toString())
                : UserMessage.builder().text(textBuilder.toString()).media(mediaList).build();
    }

    /** 대화 이력(세션)에 남길 텍스트를 조립 (첨부파일은 종류에 맞는 라벨로 표시) */
    private String buildHistoryContent(String message, List<AttachmentDTO> attachments) {
        if (attachments == null || attachments.isEmpty())
            return message != null ? message : "";

        StringBuilder sb = new StringBuilder(message != null ? message : "");
        for (AttachmentDTO att : attachments) {
            AttachmentProcessed processed = processAttachment(att);
            sb.append(" ").append(processed.historyLabel());
        }
        return sb.toString();
    }

    /** 세션에 저장된 이력을 Spring AI Message 객체 리스트로 복원 */
    private List<Message> buildHistoryMessages(ChatbotSession session) {
        List<Message> messages = new ArrayList<>();
        if (session.getHistory() != null) {
            for (ChatbotMessageDTO msg : session.getHistory()) {
                if ("user".equals(msg.getRole()))
                    messages.add(new UserMessage(msg.getContent()));
                else if ("assistant".equals(msg.getRole()))
                    messages.add(new AssistantMessage(msg.getContent()));
            }
        }
        return messages;
    }
}