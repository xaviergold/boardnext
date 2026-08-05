package com.board.controller;

import com.board.dto.agent.EventDto;
import com.board.dto.agent.MailDto;
import com.board.service.agent.CalendarService;
import com.board.service.agent.GmailService;
import com.board.service.agent.GoogleTokenService;
import com.board.service.agent.SlackNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/board/secretary")
@RequiredArgsConstructor
@Slf4j
public class RESTSecretaryController {

    private final GoogleTokenService googleTokenService;
    private final GmailService gmailService;
    private final CalendarService calendarService;
    private final SlackNotifier slack;

    // Google 연결 상태 확인
    @GetMapping("/auth/status")
    public ResponseEntity<?> checkStatus() {
        boolean connected = googleTokenService.hasValidToken(getCurrentEmail());
        return ResponseEntity.ok(Map.of("connected", connected));
    }

    // /board/secretary 대시보드용 데이터
    // mailPage: 메일 페이지 번호 (기본 1)
    // mailSize: 페이지당 메일 수 (기본 5)
 // RESTSecretaryController - 전체 반환
    @GetMapping("/summary")
    public ResponseEntity<?> getSummary(
        @RequestParam(name = "mailPage",  defaultValue = "1") int mailPage,
        @RequestParam(name = "mailSize",  defaultValue = "5") int mailSize,
        @RequestParam(name = "eventPage", defaultValue = "1") int eventPage
    ) {
        String email = getCurrentEmail();
        long start = System.currentTimeMillis();
        try {
            List<MailDto> allMails  = gmailService.getRecentMails(email);
            List<EventDto> events   = calendarService.listEvents(email, "TODAY");

            slack.toolExecuted("getSummary", email,
                "mails=" + allMails.size() + ", events=" + events.size(),
                System.currentTimeMillis() - start);

            // 전체 메일 반환 → 프론트에서 페이징
            return ResponseEntity.ok(Map.of(
                "mails",  allMails,
                "events", events
            ));
        } catch (Exception e) {
            slack.error("RESTSecretaryController.getSummary", email, "summary 조회", e);
            log.error("Secretary summary 조회 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    private String getCurrentEmail() {
        return SecurityContextHolder.getContext()
            .getAuthentication().getName();
    }
}