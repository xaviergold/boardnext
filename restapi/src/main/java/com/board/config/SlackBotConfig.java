package com.board.config;

import com.board.service.agent.SlackBotService;
import com.board.service.chatbot.SlackMessageHandler;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.jakarta_socket_mode.SocketModeApp;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageDeletedEvent;
import com.slack.api.model.event.MessageEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@Slf4j
public class SlackBotConfig implements ApplicationListener<ContextRefreshedEvent> {

    @Value("${slack.bot.token}")
    private String botToken;

    @Value("${slack.bot.app-token}")
    private String appToken;

    @Value("${slack.bot.signing-secret}")
    private String signingSecret;

    private final SlackBotService slackBotService;
    private final SlackMessageHandler slackMessageHandler;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    
    // 💡 무한 루프(Retry) 방지용 이벤트 ID 캐시
    private final Map<String, Boolean> processedEvents = new ConcurrentHashMap<>();

    private SocketModeApp socketModeApp;
    private boolean started = false;
    private App app;

    public SlackBotConfig(SlackBotService slackBotService,
                          SlackMessageHandler slackMessageHandler) {
        this.slackBotService = slackBotService;
        this.slackMessageHandler = slackMessageHandler;
    }

    @Bean
    public App slackApp() {
        app = new App(AppConfig.builder()
            .signingSecret(signingSecret)
            .singleTeamBotToken(botToken)
            .build());
        
        // slackApp() 메서드 안에 추가
        app.event(MessageDeletedEvent.class, (payload, ctx) -> {
            return ctx.ack();
        });

        // DM 메시지 수신
        app.event(MessageEvent.class, (payload, ctx) -> {
            // 💡 [1.44.2 검증 완료] event가 아닌 payload에서 eventId를 가져옵니다.
            String eventId = payload.getEventId();
            if (eventId != null && processedEvents.putIfAbsent(eventId, Boolean.TRUE) != null) {
                log.warn("Slack Retry 중복 요청 패스 - eventId: {}", eventId);
                return ctx.ack();
            }

            MessageEvent event = payload.getEvent();
            if (event.getBotId() != null) return ctx.ack();
            if (event.getSubtype() != null) return ctx.ack();
            
            String text = event.getText();
            if (text == null || text.isBlank()) return ctx.ack();
            if (text.contains("<@")) return ctx.ack();

            String channelId = event.getChannel();
            String userId    = event.getUser();
            log.info("Slack DM 수신 - user: {}, text: {}", userId, text);

            // 비동기 처리 및 즉시 ack 반환하여 슬랙 타임아웃(3초) 방지
            executorService.submit(() -> slackMessageHandler.handle(userId, channelId, text));
            
            return ctx.ack(); 
        });

        // @멘션 수신
        app.event(AppMentionEvent.class, (payload, ctx) -> {
            // 💡 [1.44.2 검증 완료] payload에서 eventId 추출
            String eventId = payload.getEventId();
            if (eventId != null && processedEvents.putIfAbsent(eventId, Boolean.TRUE) != null) {
                log.warn("Slack Retry 중복 요청 패스 - eventId: {}", eventId);
                return ctx.ack();
            }

            AppMentionEvent event = payload.getEvent();
            String text      = event.getText().replaceAll("<@[A-Z0-9]+>", "").trim();
            String channelId = event.getChannel();
            String userId    = event.getUser();
            
            if (text.isBlank()) return ctx.ack();
            log.info("Slack 멘션 수신 - user: {}, text: {}", userId, text);

            executorService.submit(() -> slackMessageHandler.handle(userId, channelId, text));
            
            return ctx.ack();
        });

        return app;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (started) return;
        started = true;
        try {
            socketModeApp = new SocketModeApp(appToken, app);
            socketModeApp.startAsync();
            log.info("Slack Socket Mode 시작됨");
        } catch (Exception e) {
            log.error("Slack Socket Mode 시작 실패: {}", e.getMessage());
        }
    }
}