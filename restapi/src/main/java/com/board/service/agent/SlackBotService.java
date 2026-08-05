package com.board.service.agent;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SlackBotService {

    private final MethodsClient client;

    // email → channelId 매핑 (메모리 캐시)
    private final Map<String, String> emailChannelMap = new ConcurrentHashMap<>();

    public SlackBotService(@Value("${slack.bot.token}") String botToken) {
        this.client = Slack.getInstance().methods(botToken);
    }

    public void sendMessage(String channelId, String text) {
        try {
            client.chatPostMessage(ChatPostMessageRequest.builder()
                .channel(channelId)
                .text(text)
                .build());
        } catch (Exception e) {
            log.error("Slack 메시지 발송 실패: {}", e.getMessage());
        }
    }

    public void saveChannelId(String email, String channelId) {
        emailChannelMap.put(email, channelId);
        log.info("[Slack] channelId 저장: email={}, channelId={}", email, channelId);
    }

    public String getChannelIdByEmail(String email) {
        return emailChannelMap.get(email);
    }

    public void deleteMessages(String channelId) {
        try {
            var history = client.conversationsHistory(r -> r.channel(channelId).limit(100));
            if (history.getMessages() == null) return;
            for (var message : history.getMessages()) {
                try {
                    client.chatDelete(r -> r.channel(channelId).ts(message.getTs()));
                    Thread.sleep(1500); // rate limit 방지
                } catch (Exception e) {
                    log.warn("[Slack] 메시지 삭제 실패: {}", e.getMessage());
                    Thread.sleep(5000); // 실패 시 5초 대기
                }
            }
            log.info("[Slack] 메시지 삭제 완료: channel={}", channelId);
        } catch (Exception e) {
            log.error("[Slack] 메시지 삭제 오류: {}", e.getMessage());
        }
    }
}