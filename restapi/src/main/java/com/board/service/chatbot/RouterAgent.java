package com.board.service.chatbot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 라우터 에이전트 - 질문 분류 전용
 *
 * gpt-5.4-mini로 질문의 카테고리를 빠르게 분류한다.
 * 답변을 생성하지 않고 오직 TaskCategory Enum 값 하나만 반환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouterAgent {

    private final ChatClient chatClient;

    private static final String ROUTER_SYSTEM_PROMPT = """
            당신은 질문을 분류하는 전문가입니다.
            사용자의 질문을 분석하여 아래 카테고리 중 정확히 하나만 반환하세요.
            반드시 아래 값 중 하나만 출력하고, 다른 설명은 절대 하지 마세요.
            
            카테고리:
            
            - ATTENDANCE_DB: 수료생 수, 수료생 명단, 미수료생, 출석률, 훈련생 수,
                             과정별 통계, 특정 훈련생 수료 여부, 출석일수, 결석일수,
                             수강생 숫자, 수료생 숫자, 과정 목록(수치 포함) 관련 질문
                             → training_course, training_student 테이블에서 조회
            
            - ATTENDANCE_RAG: 장기유급훈련 제도 설명, 지원 조건, 지원 금액,
                              훈련 내용/안내, 이력서, 경력, 학력, 자격증,
                              나의 인생, 내 경력, 내 이력, 내가 다닌 회사, 나의 건강, 
                              나에 대해, 나의 소개, 내 정보(이력서 기반) 관련 질문
                              → Vector DB 문서에서 검색
            
            - BOARD_DB: 회원 정보, 게시판, 게시물, 댓글, 좋아요,
                       회원 현황, 통계, 로그인 기록, 가입 관련 질문
                       → Oracle DB에서 조회
            
            - WEB_SEARCH: 뉴스, 날씨, 기온, 주가, 환율, 코스피, 나스닥,
                         외부 기업 매출/정보, 스포츠 결과, 최신 소식 관련 질문
                         → 웹 검색
                         
            - SECRETARY: 메일, 이메일, 일정, 캘린더, Gmail, Google Calendar,
                    메일 발송, 답장, 전달, 임시저장, 메일 삭제, 일정 등록,
                    일정 삭제, 일정 수정, Slack 메시지 삭제, 슬랙 메시지 삭제
                    → Gmail/Calendar/Slack Tool 호출
            
            - GENERAL_CHAT: 인사, 잡담, 감사, 안녕, 도움말,
                           위 카테고리에 해당 없는 것
            
            [분류 예시]
            "장기유급훈련 수료생 명단" → ATTENDANCE_DB
            "수료생 몇 명이에요?" → ATTENDANCE_DB
            "과정 목록을 수강생 숫자/수료생 숫자로 보여줘" → ATTENDANCE_DB
            "출석률 80% 미만인 훈련생" → ATTENDANCE_DB
            "김상호 수료했나요?" → ATTENDANCE_DB
            "장기유급훈련 지원 조건이 뭔가요?" → ATTENDANCE_RAG
            "장기유급훈련 지원 금액 얼마?" → ATTENDANCE_RAG
            "내 이력서에서 SK네트웍스" → ATTENDANCE_RAG
            "나의 인생은?" → ATTENDANCE_RAG
            "나에 대해 설명해줘" → ATTENDANCE_RAG
            "내 경력 보여줘" → ATTENDANCE_RAG
            "나의 연인 최현아에 대해" → ATTENDANCE_RAG
            "회원 수가 몇 명이야?" → BOARD_DB
            "내 프로필 이미지" → BOARD_DB
            "오늘 국제 뉴스" → WEB_SEARCH
            "날씨 알려줘" → WEB_SEARCH
            "오늘 메일 목록" → SECRETARY
            "이 메일 본문 보여줘" → SECRETARY
            "메일 보내줘" → SECRETARY
            "오늘 일정" → SECRETARY
            "안녕하세요" → GENERAL_CHAT
            "슬랙 메시지 보내줘" → SECRETARY
            "Slack으로 알려줘" → SECRETARY
            "슬랙 메세지 삭제해 주세요" → SECRETARY
            """;

    /**
     * 질문을 분석하여 TaskCategory를 반환한다.
     * 분류 실패 시 GENERAL_CHAT으로 폴백한다.
     */
    public TaskCategory classify(String message) {
        if (message == null || message.isBlank()) return TaskCategory.GENERAL_CHAT;

        try {
            String result = chatClient.prompt()
                    .system(ROUTER_SYSTEM_PROMPT)
                    .user(message)
                    .call()
                    .content();

            if (result == null) return TaskCategory.GENERAL_CHAT;

            String trimmed = result.trim().toUpperCase()
                    .replaceAll("[^A-Z_]", "");

            TaskCategory category = TaskCategory.valueOf(trimmed);
            log.info("[Router] 질문 분류: '{}' → {}", message, category);
            return category;

        } catch (IllegalArgumentException e) {
            log.warn("[Router] 분류 실패, GENERAL_CHAT으로 폴백: '{}'", message);
            return TaskCategory.GENERAL_CHAT;
        } catch (Exception e) {
            log.error("[Router] 오류 발생, GENERAL_CHAT으로 폴백: {}", e.getMessage());
            return TaskCategory.GENERAL_CHAT;
        }
    }
}