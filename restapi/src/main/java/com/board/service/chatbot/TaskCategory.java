package com.board.service.chatbot;

/**
 * 챗봇 질문 카테고리 분류 Enum
 * RouterAgent가 질문을 분석하여 이 중 하나로 분류한다.
 */
public enum TaskCategory {

    ATTENDANCE_RAG,   // 훈련 제도/내용/안내 질문 → Vector DB (장기유급훈련안내.txt 등)
    ATTENDANCE_DB,    // 수료생 수/명단/출석률 등 수치 질문 → PostgreSQL (training_student)
    BOARD_DB,         // 회원/게시판/게시물/댓글/통계 → Oracle Tool Calling
    WEB_SEARCH,       // 뉴스/날씨/주가/환율/외부 기업 정보 → 웹검색
    SECRETARY,        // slack 메세지 삭제 등의 업무 처리, 메일/캘린더 관련 → Gmail/Calendar Tool
    GENERAL_CHAT      // 일반 대화/인사/잡담
}