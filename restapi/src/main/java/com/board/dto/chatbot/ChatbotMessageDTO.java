package com.board.dto.chatbot;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatbotMessageDTO {

    private String role;          // "user" | "assistant" | "system"
    private String content;
    private LocalDateTime timestamp;
    private List<String> imageUrls;

    public static ChatbotMessageDTO ofUser(String content) {
        return ChatbotMessageDTO.builder()
                .role("user")
                .content(content)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ChatbotMessageDTO ofAssistant(String content) {
        return ChatbotMessageDTO.builder()
                .role("assistant")
                .content(content)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ChatbotMessageDTO ofAssistant(String content, List<String> imageUrls) {
        return ChatbotMessageDTO.builder()
                .role("assistant")
                .content(content)
                .timestamp(LocalDateTime.now())
                .imageUrls(imageUrls)
                .build();
    }
}