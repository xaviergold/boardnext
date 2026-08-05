package com.board.service.chatbot;

import com.board.dto.chatbot.ChatbotRequestDTO;
import com.board.dto.chatbot.ChatbotResponseDTO;
import com.board.entity.repository.MemberRepository;
import com.board.service.agent.SlackBotService;
import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.response.users.UsersInfoResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@Slf4j
public class SlackMessageHandler {

    private final ChatbotService chatbotService;
    private final SlackBotService slackBotService;
    private final MemberRepository memberRepository;
    private final MethodsClient methodsClient;

    public SlackMessageHandler(
            ChatbotService chatbotService,
            SlackBotService slackBotService,
            MemberRepository memberRepository,
            @Value("${slack.bot.token}") String botToken) {
        this.chatbotService = chatbotService;
        this.slackBotService = slackBotService;
        this.memberRepository = memberRepository;
        this.methodsClient = Slack.getInstance().methods(botToken);
    }

    public void handle(String userId, String channelId, String text) {
        try {
            String email = getEmailBySlackUserId(userId);
            if (email == null) {
                slackBotService.sendMessage(channelId,
                    "이메일 정보를 가져올 수 없습니다. 관리자에게 문의하세요.");
                return;
            }
            log.info("Slack 사용자 이메일: {}", email);

            // email → channelId 저장
            slackBotService.saveChannelId(email, channelId);

            String role = memberRepository.findById(email)
                .map(m -> m.getRole())
                .orElse("USER");

            Authentication auth = new UsernamePasswordAuthenticationToken(
                email, null,
                List.of(new SimpleGrantedAuthority(role))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);

            ChatbotRequestDTO request = new ChatbotRequestDTO();
            request.setSessionId("slack-" + userId);
            request.setMessage(text);
            request.setSlackChannelId(channelId);

            ChatbotResponseDTO response = chatbotService.chat(request);

            String reply = response.isSuccess()
                ? response.getReply()
                : "오류가 발생했습니다: " + response.getErrorMessage();

            slackBotService.sendMessage(channelId, reply);
        } catch (Exception e) {
            log.error("Slack AI 처리 실패: {}", e.getMessage());
            slackBotService.sendMessage(channelId, "처리 중 오류가 발생했습니다.");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String getEmailBySlackUserId(String userId) {
        try {
            UsersInfoResponse response = methodsClient.usersInfo(r -> r.user(userId));
            if (response.isOk()) {
                String email = response.getUser().getProfile().getEmail();
                log.info("Slack userId: {} → email: {}", userId, email);
                return email;
            }
            log.error("Slack 사용자 조회 실패: {}", response.getError());
            return null;
        } catch (Exception e) {
            log.error("Slack 이메일 조회 오류: {}", e.getMessage());
            return null;
        }
    }
}