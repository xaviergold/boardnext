package com.board.service.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
public class SlackNotifier {

    @Value("${slack.webhook.url}")
    private String webhookUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    private void send(String text) {
        CompletableFuture.runAsync(() -> {
            try {
                restTemplate.postForObject(
                    webhookUrl,
                    Map.of("text", text),
                    String.class
                );
            } catch (Exception e) {
                log.warn("Slack 알림 전송 실패: {}", e.getMessage());
            }
        });
    }

    public void toolExecuted(String toolName, String email, String params, long elapsedMs) {
        /* send("""
            🔧 *[Tool 실행]* `%s`
            사용자: %s
            파라미터: %s
            소요시간: %dms
            """.formatted(toolName, email, params, elapsedMs)); */
        log.debug("[Tool 실행] {} | {} | {} | {}ms", toolName, email, params, elapsedMs);
    }

    public void toolFailed(String toolName, String email, Exception e) {
        send("""
            ❌ *[Tool 실패]* `%s`
            사용자: %s
            오류: %s
            """.formatted(toolName, email, e.getMessage()));
    }

    public void tokenExpiringSoon(String email, long minutesLeft) {
        /* send("""
            ⚠️ *[토큰 경고]* Google Access Token 만료 임박
            사용자: %s
            만료까지: %d분
            """.formatted(email, minutesLeft));*/            
        log.debug("[토큰 경고] {} | 만료까지 {}분", email, minutesLeft);
    }

    public void tokenRefreshed(String email, boolean success) {
    	/*
        if (success) {
            send("🔄 *[토큰 갱신]* 성공 | 사용자: " + email);
        } else {
            send("🚨 *[토큰 갱신 실패]* | 사용자: " + email + "\n→ 재로그인 필요");
        } */
        log.debug("[토큰 갱신] {} | {}", success ? "성공" : "실패", email);
    }

    public void error(String location, String email, String userMessage, Exception e) {
        send("""
            🚨 *[에러]* `%s`
            사용자: %s
            입력: "%s"
            오류: %s
            """.formatted(location, email, userMessage, e.getMessage()));
    }
}