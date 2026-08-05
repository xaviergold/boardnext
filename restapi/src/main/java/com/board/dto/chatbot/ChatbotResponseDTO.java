package com.board.dto.chatbot;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatbotResponseDTO {
    private String sessionId;
    private String reply;
    private List<ChatbotMessageDTO> history;
    private boolean success;
    private String errorMessage;
    private List<String> imageUrls; // 이미지 URL 목록 추가

    public static ChatbotResponseDTO success(String sessionId, String reply,
            List<ChatbotMessageDTO> history, List<String> imageUrls) {
        return ChatbotResponseDTO.builder()
                .sessionId(sessionId)
                .reply(reply)
                .history(history)
                .imageUrls(imageUrls)
                .success(true)
                .build();
    }

    public static ChatbotResponseDTO error(String sessionId, String errorMessage) {
        return ChatbotResponseDTO.builder()
                .sessionId(sessionId)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}