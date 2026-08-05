package com.board.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.board.dto.chatbot.ChatbotMessageDTO;
import com.board.dto.chatbot.ChatbotRequestDTO;
import com.board.dto.chatbot.ChatbotRequestDTO.AttachmentDTO;
import com.board.dto.chatbot.ChatbotResponseDTO;
import com.board.service.chatbot.ChatbotService;
import com.board.service.chatbot.ChatExportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ================================================================================
 * 챗봇 REST API 진입점 (Controller Layer)
 * ================================================================================
 * 프론트엔드(Next.js)가 호출하는 챗봇 관련 HTTP 엔드포인트를 모아놓은 컨트롤러.
 * 실제 로직은 전혀 갖고 있지 않고, 요청을 파싱해서 아래 두 서비스 중 하나로 그대로
 * 위임(delegate)한 뒤 결과를 HTTP 응답으로 감싸서 반환하는 역할만 한다.
 *
 *   ChatbotService     : 실제 AI 답변 생성(RouterAgent 분류 → Tool/RAG 호출 → LLM 응답) 전담.
 *                         이 컨트롤러가 호출하는 순간부터는 이전에 정리한 4개 클래스
 *                         (ChatbotService/BoardImageService/BoardQueryService/
 *                         AIExternalSearchService)의 연동 구조가 그대로 이어진다.
 *   ChatExportService   : 완성된 대화 이력을 엑셀/워드 파일로 변환하는, AI와 무관한
 *                         순수 파일 생성 유틸리티. 챗봇 응답 생성 흐름과는 완전히 별개.
 *
 * [엔드포인트 ↔ ChatbotService 메소드 매핑]
 *   POST   /api/chatbot                  → chatbotService.chat(request)        (동기, 완성된 답변 한 번에 반환)
 *   GET    /api/chatbot/stream           → chatbotService.streamChat(...)      (SSE 스트리밍, Flux<String>)
 *   GET    /api/chatbot/history/{id}     → chatbotService.getHistory(sessionId)
 *   DELETE /api/chatbot/session/{id}     → chatbotService.clearSession(sessionId)
 *
 * [POST /api/chatbot 요청 하나가 처리되는 전체 호출 체인 요약]
 *   RESTChatbotController.chat()
 *     └─ ChatbotService.chat(request)
 *          ├─ ChatbotSessionManager        : Redis에서 세션(대화 이력) 조회/생성
 *          ├─ buildUserMessage()           : 첨부파일(이미지/문서) 처리
 *          ├─ BoardImageService            : "사진 보여줘" 류 요청 선조회 (LLM 호출 전 직접 호출)
 *          ├─ RouterAgent.classify()       : 질문 의도 분류 (LLM 호출 1회)
 *          ├─ applyCategoryRouting()       : 카테고리별 Tool/advisor 동적 장착
 *          │     ├─ BoardQueryService          (BOARD_DB)   - 자연어→SQL 변환 후 Oracle 조회
 *          │     ├─ SecretaryTools            (SECRETARY)  - Gmail/Calendar/Slack
 *          │     ├─ AIExternalSearchService   (전역/WEB_SEARCH) - 네이버 뉴스·이미지, 기상청 날씨
 *          │     └─ QuestionAnswerAdvisor(vectorStore) (ATTENDANCE_RAG, MASTER 전용) - pgvector RAG
 *          ├─ ChatClient.call()            : 실제 LLM 호출 (필요 시 위 Tool들을 LLM이 스스로 호출)
 *          ├─ parseReply()                 : 답변 속 이미지 URL 파싱/분리
 *          └─ sessionManager.saveToRedis() : 대화 이력 갱신 저장
 *
 * GET /api/chatbot/stream도 내부적으로는 완전히 동일한 체인을 타되, chat() 대신
 * streamChat()이 호출되고 최종 결과가 한 번에 오는 대신 Flux<String>으로 토큰 단위
 * 스트리밍된다는 점만 다르다 (수정 D로 두 메소드의 라우팅 로직은 공통화되어 있음).
 * ================================================================================
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RESTChatbotController {

	// 챗봇 핵심 로직(응답 생성, 히스토리 관리 등)을 담당하는 서비스
    private final ChatbotService chatbotService;
    
    // 대화 내용을 엑셀/워드 파일로 변환하는 서비스
    private final ChatExportService chatExportService;
    
    // JSON <-> 자바 객체 변환용 (첨부파일 파라미터를 JSON 문자열로 받을 때 사용)
    private final ObjectMapper objectMapper;

    // POST /api/chatbot - 일반 채팅 (파일 첨부 지원)
    // 요청 전체를 한 번에 받아 완성된 응답(ChatbotResponseDTO)을 한 번에 반환
    // [호출 체인] 이 메소드는 요청을 그대로 ChatbotService.chat(request)에 위임만 한다.
    //   그 내부에서 세션조회 → 이미지 선조회(BoardImageService) → 의도분류(RouterAgent)
    //   → Tool/RAG 장착(BoardQueryService/SecretaryTools/AIExternalSearchService/VectorStore)
    //   → LLM 호출 → 응답파싱 → Redis 저장까지 전부 처리된 뒤 완성된 DTO가 돌아온다.
    //   (클래스 상단 Javadoc의 "호출 체인 요약" 참고)
    @PostMapping("/api/chatbot")
    public ResponseEntity<ChatbotResponseDTO> chat(@RequestBody ChatbotRequestDTO request) {
        log.info("POST /api/chatbot - 챗봇 sessionId: {}, 첨부물 갯수: {}",
                request.getSessionId(),
                request.getAttachments() != null ? request.getAttachments().size() : 0);
        return ResponseEntity.ok(chatbotService.chat(request));
    }

    
    //GET /api/chatbot/stream - 스트리밍 채팅 SSE (파일 첨부 지원) 
    //SSE(Server-Sent Events)는 GET 요청만 지원하기 때문에 첨부파일을 JSON 문자열로 
    //   인코딩해서 쿼리스트링에 실어 보내야 함
    //produces = MediaType.TEXT_EVENT_STREAM_VALUE 
    //  --> HTTP 응답 헤더에 Content-Type: text/event-stream을 설정 --> SSE 프로토콜
    //  --> Spring WebFlux가 자동으로 각 문자열 조각을 SSE 이벤트 형식(data: ...\n\n)으로 감싸서 커넥션을 끊지 않고 계속 전송
    //  --> 브라우저(프론트엔드)는 EventSource API로 이 스트림을 구독해서, 데이터가 도착할 때마다 콜백을 실행
    // [호출 체인] 첨부파일 문자열(attachments)을 JSON→List<AttachmentDTO>로 역직렬화한 뒤,
    //   chatbotService.streamChat(sessionId, message, attachmentList)에 그대로 위임한다.
    //   내부 라우팅/Tool 장착 로직은 chat()과 완전히 동일(applyCategoryRouting 공통 사용)하고,
    //   차이는 LLM 응답을 한 번에 안 기다리고 Flux<String>으로 토큰 단위 스트리밍한다는 점,
    //   그리고 이미지가 선조회됐을 경우 본문 스트림 맨 앞에 "IMAGE_URLS:..." 마커 청크를
    //   먼저 흘려보낸다는 점이다. 프론트는 EventSource로 이 청크들을 받아 순서대로 렌더링한다.
    @GetMapping(value = "/api/chatbot/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    //Flux<T>는 0개부터 N개까지의 데이터가 시간을 두고 순차적으로 발행(emit)될 수 있는 비동기 스트림을 표현하는 
    //Reactor 라이브러리 타입으로 ChatGPT나 Claude 같은 챗봇 UI에서 답변 글자가 타이핑되듯 하나씩 나타나는 효과를 만듬
    public Flux<String> streamChat(
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "message") String message,
            @RequestParam(name = "attachments", required = false) String attachments) {

        log.info("GET /api/chatbot/stream - 챗봇 sessionId: {}, 첨부물 존재 여부: {}",
                sessionId, attachments != null ? "있음" : "없음");
        //Collections.emptyList() --> 비어있는 불변(immutable) List를 반환하는 정적(static) 메서드
        //내부적으로는 요소가 하나도 없는 싱글턴(singleton) 리스트 객체를 반환. 
        //new ArrayList<>()처럼 새 객체를 만드는 게 아니라, 
        //이미 만들어진 빈 리스트 하나를 계속 공유해서 반환하는 방식이라 메모리 효율이 좋고 NPE 발생을 억지함
        List<AttachmentDTO> attachmentList = Collections.emptyList();
        if (attachments != null && !attachments.isBlank()) {
            try {
            	//readValue 메소드 : Jackson이 문자열 형태의 JSON 데이터를 객체로 반환(역직렬화)
            	//Jackson이 JSON을 파싱할 때는 런타임에 "이 JSON 배열을 무슨 타입의 객체 리스트로 만들어야 하는지" 알아야 함. 
            	//new TypeReference<List<AttachmentDTO>>() {} 
            	//  --> TypeReference<List<AttachmentDTO>>() 객체를 상속받는 자식 익명 클래스를 만든다는 의미
            	//  --> TypeReference는 Jackson 라이브러리가 자바의 "제네릭 타입 소거(Type Erasure)" 문제를 
            	//      우회하기 위해 제공하는 추상 클래스로 자바에서 제네릭 타입 파라미터(<...>)는 인스턴스 자체에서는 소거되지만, 
            	//      클래스 상속 구조(부모-자식 관계)에서는 소거되지 않고 남아 있기 때문에 런타임시에도 소거되지 않고 남아서   
            	//      List<AttachmentDTO> attachmentList에 안전하게 값이 할당됨 
                attachmentList = objectMapper.readValue(attachments,
                        new TypeReference<List<AttachmentDTO>>() {});
            } catch (Exception e) {
                log.warn("첨부파일 파싱 실패: {}", e.getMessage());
            }
        }
        return chatbotService.streamChat(sessionId, message, attachmentList);
    }
    
    //GET /api/chatbot/history/{sessionId} - 대화 히스토리 조회
    // [호출 체인] chatbotService.getHistory(sessionId) → sessionManager.getOrCreate(sessionId)
    //   → Redis에 저장된 ChatbotSession의 history(List<ChatbotMessageDTO>) 그대로 반환.
    //   LLM/Tool 호출은 전혀 발생하지 않는 단순 조회.
    @GetMapping("/api/chatbot/history/{sessionId}")
    public ResponseEntity<List<ChatbotMessageDTO>> getHistory(
            @PathVariable(name = "sessionId") String sessionId) {
        log.info("GET /api/chatbot/history/{}", sessionId);
        return ResponseEntity.ok(chatbotService.getHistory(sessionId));
    }
    
    //DELETE /api/chatbot/session/{sessionId} - 대화 초기화    
    // [호출 체인] chatbotService.clearSession(sessionId) → sessionManager.removeSession(sessionId)
    //   → Redis에서 해당 세션 키 삭제. 다음 요청부터는 getOrCreate()가 새 세션을 생성한다.
    @DeleteMapping("/api/chatbot/session/{sessionId}")
    public ResponseEntity<Map<String, String>> clearSession(
            @PathVariable(name = "sessionId") String sessionId) {
        log.info("DELETE /api/chatbot/session/{}", sessionId);
        chatbotService.clearSession(sessionId);
        return ResponseEntity.ok(Map.of(
                "message", "대화가 초기화되었습니다.",
                "sessionId", sessionId));
    }

    
    // ============================================================================
    // 아래 4개 export 엔드포인트는 ChatbotService/RouterAgent/Tool 체인과 무관하다.
    // 프론트에서 이미 갖고 있는 대화 이력(messages)을 그대로 body로 보내면,
    // ChatExportService가 POI(Apache POI) 등을 이용해 엑셀/워드 바이너리로 변환만 해준다.
    // ============================================================================

    //POST /api/chatbot/export/excel - 대화 내용 엑셀 내보내기  
    @PostMapping("/api/chatbot/export/excel")
    public ResponseEntity<byte[]> exportToExcel(@RequestBody Map<String, Object> body) {
        try {
            String title = (String) body.getOrDefault("title", "대화내용");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> messages =
                    (List<Map<String, String>>) body.get("messages");
            byte[] data = chatExportService.exportToExcel(title, messages);
            String fileName = java.net.URLEncoder.encode(title + ".xlsx",
                    java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + fileName)
                    .contentType(org.springframework.http.MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(data);
        } catch (Exception e) {
            log.error("[ChatExport] 엑셀 내보내기 실패", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    //POST /api/chatbot/export/table - 마크다운 표 → 엑셀 변환    
    @PostMapping("/api/chatbot/export/table")
    public ResponseEntity<byte[]> exportTableToExcel(@RequestBody Map<String, Object> body) {
        try {
            String title = (String) body.getOrDefault("title", "데이터");
            String table = (String) body.getOrDefault("table", "");
            byte[] data = chatExportService.exportTableToExcel(title, table);
            String fileName = java.net.URLEncoder.encode(title + ".xlsx",
                    java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + fileName)
                    .contentType(org.springframework.http.MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(data);
        } catch (Exception e) {
            log.error("[ChatExport] 표 엑셀 내보내기 실패", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * POST /api/chatbot/export/word - 대화 내용 워드 내보내기
     */
    @PostMapping("/api/chatbot/export/word")
    public ResponseEntity<byte[]> exportToWord(@RequestBody Map<String, Object> body) {
        try {
            String title = (String) body.getOrDefault("title", "대화내용");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> messages =
                    (List<Map<String, String>>) body.get("messages");
            byte[] data = chatExportService.exportToWord(title, messages);
            String fileName = java.net.URLEncoder.encode(title + ".docx",
                    java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + fileName)
                    .contentType(org.springframework.http.MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                    .body(data);
        } catch (Exception e) {
            log.error("[ChatExport] 워드 내보내기 실패", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}