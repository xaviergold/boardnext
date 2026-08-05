package com.board.dto.chatbot;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatbotRequestDTO {
    private String sessionId;   // 대화 세션 ID
    private String slackChannelId; // Slack 채널 ID 저장
    private String message;     // 사용자 입력 메시지
    private List<AttachmentDTO> attachments; // 첨부파일 목록 (없으면 null)

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttachmentDTO {
        private String name;        // 파일명 (예: photo.jpg)
        private String mimeType;    // MIME 타입 (예: image/jpeg, application/pdf)
        private String base64Data;  // base64 인코딩된 파일 데이터
    }
}