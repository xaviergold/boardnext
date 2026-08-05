package com.board.session;

import com.board.dto.chatbot.ChatbotMessageDTO;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ChatbotSession implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sessionId;
    
    private List<ChatbotMessageDTO> messages = new ArrayList<>();

    // 마지막으로 조회한 프로필 대상 (Redis에 자동 직렬화됨)
    // 예: "김민", "self:xaviergold@gmail.com"
    private String lastProfileTarget;
    
    // Slack 채널 ID 저장
    private String slackChannelId;

    private static final int MAX_HISTORY_SIZE = 20;

    public ChatbotSession(String sessionId) {
        this.sessionId = sessionId;
        this.messages = new ArrayList<>();
    }

    /** 사용자 메시지 추가 */
    public void addUserMessage(String content) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        messages.add(ChatbotMessageDTO.ofUser(content));
        trimHistory();
    }

    /** AI 응답 메시지 추가 */
    public void addAssistantMessage(String content) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        messages.add(ChatbotMessageDTO.ofAssistant(content));
    }

    // imageUrls 포함 버전
    public void addAssistantMessage(String content, List<String> imageUrls) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        messages.add(ChatbotMessageDTO.ofAssistant(content, imageUrls));
    }

    /**
     * 읽기 전용 히스토리 반환
     * ★ [중요] Jackson이 이 메서드를 보고 "history"라는 가짜 필드를 Redis JSON에
     * 저장하지 않도록 명시적으로 제외 처리(무시)합니다.
     */
    @JsonIgnore
    public List<ChatbotMessageDTO> getHistory() {
        if (this.messages == null) return Collections.emptyList();
        return Collections.unmodifiableList(messages);
    }

    /** 대화 초기화 */
    public void clear() {
        if (this.messages != null) {
            messages.clear();
        }
    }

    /** 오래된 메시지 삭제 */
    private void trimHistory() {
        while (messages.size() > MAX_HISTORY_SIZE) {
            messages.remove(0);
        }
    }
}