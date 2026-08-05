package com.board.service.agent;

import com.board.dto.agent.EventDto;
import com.board.dto.agent.MailDto;
import com.board.session.ChatbotSession;
import com.board.session.ChatbotSessionManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class SecretaryTools {

    private final GmailService gmailService;
    private final CalendarService calendarService;
    private final SlackNotifier slack;
    private final ChatbotSessionManager sessionManager;
    private final SlackBotService slackBotService;

    // 오늘 받은 메일 전체 조회
    @Tool(description = "사용자의 오늘 받은 이메일 목록을 가져옵니다. '메일 확인', '받은 메일 보여줘', '오늘 메일 몇 건' 요청에 사용합니다.")
    public String getRecentMails() {
        String email = getCurrentEmail();
        long start = System.currentTimeMillis();
        try {
            List<MailDto> mails = gmailService.getRecentMails(email);
            slack.toolExecuted("getRecentMails", email,
                "total=" + mails.size(), System.currentTimeMillis() - start);
            StringBuilder sb = new StringBuilder();
            sb.append("오늘 받은 메일 총 ").append(mails.size()).append("건:\n");
            for (int i = 0; i < mails.size(); i++) {
                MailDto m = mails.get(i);
                String body = (m.getBody() != null && !m.getBody().isBlank())
                        ? m.getBody() : m.getSnippet();
                sb.append(String.format(
                    "%d. ID: %s | 발신: %s | 제목: %s\n[본문]\n%s\n\n",
                    i + 1, m.getId(), m.getFrom(),
                    m.getSubject() != null ? m.getSubject() : "(제목 없음)",
                    body));
            }
            return sb.toString();
        } catch (Exception e) {
            slack.toolFailed("getRecentMails", email, e);
            throw new SecretaryAuthException("메일 조회 실패: " + e.getMessage());
        }
    }

    // 메일 발송 (CC 포함)
    @Tool(description = "특정 메일의 전체 본문을 조회합니다. '메일 본문 보여줘', '이 메일 내용 전체 보여줘' 요청에 사용합니다.")
    public String getMailBody(
        @ToolParam(description = "조회할 메일 제목 (일부만 입력해도 됨)") String subject
    ) {
        String email = getCurrentEmail();
        long start = System.currentTimeMillis();
        try {
            MailDto mail = gmailService.findMailBySubject(email, subject);
            if (mail == null) {
                return "'" + subject + "' 메일을 찾을 수 없습니다.";
            }
            String body = mail.getBody();
            if (body == null || body.isBlank()) {
                body = mail.getSnippet();
            }
            slack.toolExecuted("getMailBody", email,
                "subject=" + subject, System.currentTimeMillis() - start);
            return String.format(
                "[메일 본문]\n발신: %s\n제목: %s\n날짜: %s\n\n%s",
                mail.getFrom(), mail.getSubject(), mail.getDate(), body);
        } catch (Exception e) {
            slack.toolFailed("getMailBody", email, e);
            throw new SecretaryAuthException("메일 본문 조회 실패: " + e.getMessage());
        }
    }

    @Tool(description = "새 이메일을 발송합니다. '~에게 메일 보내줘' 요청에 사용합니다. 참조(cc)는 선택사항으로 여러 명이면 콤마로 구분합니다. 발송 전 반드시 내용을 보여주고 사용자 확인을 받아야 합니다.")
    public String sendMail(
        @ToolParam(description = "수신자 이메일") String to,
        @ToolParam(description = "제목") String subject,
        @ToolParam(description = "본문 내용") String body,
        @ToolParam(description = "참조(CC) 이메일, 없으면 빈 문자열") String cc
    ) {
        String email = getCurrentEmail();
        long start = System.currentTimeMillis();
        try {
            gmailService.sendMail(email, to, subject, body, cc);
            slack.toolExecuted("sendMail", email,
                "to=" + to + ", cc=" + cc + ", subject=" + subject,
                System.currentTimeMillis() - start);
            return "메일을 발송했습니다. 수신자: " + to +
                (cc != null && !cc.isBlank() ? ", 참조: " + cc : "");
        } catch (Exception e) {
            slack.toolFailed("sendMail", email, e);
            throw new SecretaryAuthException("메일 발송 실패: " + e.getMessage());
        }
    }

    // 답장 (Reply)
    @Tool(description = "이메일에 답장합니다. '답장해줘', '~메일에 reply해줘' 요청에 사용합니다. 참조(cc)는 선택사항입니다.")
    public String replyMail(
        @ToolParam(description = "답장할 메일 제목 (일부만 입력해도 됨)") String subject,
        @ToolParam(description = "답장 본문 내용") String body,
        @ToolParam(description = "참조(CC) 이메일, 없으면 빈 문자열") String cc
    ) {
        String email = getCurrentEmail();
        long start = System.currentTimeMillis();
        try {
            MailDto target = gmailService.findMailBySubject(email, subject);
            if (target == null) {
                return "'" + subject + "' 메일을 찾을 수 없습니다.";
            }
            gmailService.replyMail(email, target.getId(), body, cc);
            slack.toolExecuted("replyMail", email,
                "subject=" + subject, System.currentTimeMillis() - start);
            return "'" + subject + "' 메일에 답장했습니다.";
        } catch (Exception e) {
            slack.toolFailed("replyMail", email, e);
            throw new SecretaryAuthException("답장 실패: " + e.getMessage());
        }
    }

    // 전체 답장 (Reply All)
    @Tool(description = "이메일에 전체 답장합니다. '전체 답장해줘', '~메일에 reply all해줘' 요청에 사용합니다.")
    public String replyAllMail(
        @ToolParam(description = "전체 답장할 메일 제목 (일부만 입력해도 됨)") String subject,
        @ToolParam(description = "답장 본문 내용") String body
    ) {
        String email = getCurrentEmail();
        long start = System.currentTimeMillis();
        try {
            MailDto target = gmailService.findMailBySubject(email, subject);
            if (target == null) {
                return "'" + subject + "' 메일을 찾을 수 없습니다.";
            }
            gmailService.replyAllMail(email, target.getId(), body);
            slack.toolExecuted("replyAllMail", email,
                "subject=" + subject, System.currentTimeMillis() - start);
            return "'" + subject + "' 메일에 전체 답장했습니다.";
        } catch (Exception e) {
            slack.toolFailed("replyAllMail", email, e);
            throw new SecretaryAuthException("전체 답장 실패: " + e.getMessage());
        }
    }

    // 전달 (Forward)
    @Tool(description = "이메일을 다른 사람에게 전달합니다. '전달해줘', '~메일 forwarding해줘' 요청에 사용합니다. 참조(cc)는 선택사항입니다.")
    public String forwardMail(
        @ToolParam(description = "전달할 메일 제목 (일부만 입력해도 됨)") String subject,
        @ToolParam(description = "전달받을 이메일 주소") String to,
        @ToolParam(description = "전달 시 추가할 메시지 (없으면 빈 문자열)") String body,
        @ToolParam(description = "참조(CC) 이메일, 없으면 빈 문자열") String cc
    ) {
        String email = getCurrentEmail();
        long start = System.currentTimeMillis();
        try {
            MailDto target = gmailService.findMailBySubject(email, subject);
            if (target == null) {
                return "'" + subject + "' 메일을 찾을 수 없습니다.";
            }
            gmailService.forwardMail(email, target.getId(), to, body, cc);
            slack.toolExecuted("forwardMail", email,
                "subject=" + subject + ", to=" + to,
                System.currentTimeMillis() - start);
            return "'" + subject + "' 메일을 " + to + "에게 전달했습니다.";
        } catch (Exception e) {
            slack.toolFailed("forwardMail", email, e);
            throw new SecretaryAuthException("전달 실패: " + e.getMessage());
        }
    }

    // 임시보관함 저장 (CC 포함)
    @Tool(description = "이메일을 임시보관함에 저장합니다. '초안 저장', '나중에 검토' 요청에 사용합니다. 참조(cc)는 선택사항입니다.")
    public String saveDraft(
        @ToolParam(description = "수신자 이메일") String to,
        @ToolParam(description = "제목") String subject,
        @ToolParam(description = "본문") String body,
        @ToolParam(description = "참조(CC) 이메일, 없으면 빈 문자열") String cc
    ) {
        String email = getCurrentEmail();
        long start = System.currentTimeMillis();
        try {
            gmailService.saveDraft(email, to, subject, body, cc);
            slack.toolExecuted("saveDraft", email,
                "to=" + to, System.currentTimeMillis() - start);
            return "임시보관함에 저장했습니다.";
        } catch (Exception e) {
            slack.toolFailed("saveDraft", email, e);
            throw new SecretaryAuthException("초안 저장 실패: " + e.getMessage());
        }
    }

    // 메일 삭제
    @Tool(description = "이메일을 휴지통으로 이동합니다. '메일 삭제해줘', '~메일 지워줘' 요청에 사용합니다.")
    public String deleteMail(
        @ToolParam(description = "삭제할 메일 제목 (일부만 입력해도 됨)") String subject
    ) {
        String email = getCurrentEmail();
        long start = System.currentTimeMillis();
        try {
            MailDto target = gmailService.findMailBySubject(email, subject);
            if (target == null) {
                return "'" + subject + "' 메일을 찾을 수 없습니다.";
            }
            gmailService.deleteMail(email, target.getId());
            slack.toolExecuted("deleteMail", email,
                "subject=" + subject, System.currentTimeMillis() - start);
            return "'" + subject + "' 메일을 휴지통으로 이동했습니다.";
        } catch (Exception e) {
            slack.toolFailed("deleteMail", email, e);
            throw new SecretaryAuthException("메일 삭제 실패: " + e.getMessage());
        }
    }

    // 일정 조회
    @Tool(description = "오늘 또는 이번 주 일정을 조회합니다. '오늘 일정', '이번 주 미팅' 요청에 사용합니다.")
    public String listEvents(
        @ToolParam(description = "조회 기간: TODAY 또는 THIS_WEEK") String period
    ) {
        String email = getCurrentEmail();
        long start = System.currentTimeMillis();
        try {
            List<EventDto> events = calendarService.listEvents(email, period);
            slack.toolExecuted("listEvents", email,
                "period=" + period, System.currentTimeMillis() - start);
            if (events.isEmpty()) return "등록된 일정이 없습니다.";
            return events.stream()
                .map(e -> "%s | %s ~ %s | %s"
                    .formatted(e.getTitle(), e.getStart(), e.getEnd(),
                        e.getLocation() != null ? e.getLocation() : "장소 미정"))
                .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            slack.toolFailed("listEvents", email, e);
            throw new SecretaryAuthException("일정 조회 실패: " + e.getMessage());
        }
    }

    // 일정 생성
    @Tool(description = "Google Calendar에 일정을 등록합니다. '일정 잡아줘', '미팅 추가해줘' 요청에 사용합니다.")
    public String createCalendarEvent(
        @ToolParam(description = "일정 제목") String title,
        @ToolParam(description = "시작 일시 (ISO 8601, 예: 2026-06-22T14:00:00)") String startDatetime,
        @ToolParam(description = "종료 일시 (ISO 8601)") String endDatetime,
        @ToolParam(description = "장소 또는 회의링크 (선택)") String location
    ) {
        String email = getCurrentEmail();
        long start = System.currentTimeMillis();
        try {
            EventDto event = calendarService.createEvent(
                email, title, startDatetime, endDatetime, location);
            slack.toolExecuted("createCalendarEvent", email,
                "title=" + title + ", start=" + startDatetime,
                System.currentTimeMillis() - start);
            return "일정이 등록되었습니다: " + event.getHtmlLink();
        } catch (Exception e) {
            slack.toolFailed("createCalendarEvent", email, e);
            throw new SecretaryAuthException("일정 등록 실패: " + e.getMessage());
        }
    }

    // 일정 삭제
    @Tool(description = "Google Calendar 일정을 삭제합니다. '일정 삭제해줘', '~일정 지워줘' 요청에 사용합니다.")
    public String deleteCalendarEvent(
        @ToolParam(description = "삭제할 일정 제목") String title
    ) {
        String email = getCurrentEmail();
        long start = System.currentTimeMillis();
        try {
            String eventId = calendarService.findEventIdByTitle(email, title);
            if (eventId == null) {
                return "'" + title + "' 일정을 찾을 수 없습니다.";
            }
            calendarService.deleteEvent(email, eventId);
            slack.toolExecuted("deleteCalendarEvent", email,
                "title=" + title, System.currentTimeMillis() - start);
            return "'" + title + "' 일정을 삭제했습니다.";
        } catch (Exception e) {
            slack.toolFailed("deleteCalendarEvent", email, e);
            throw new SecretaryAuthException("일정 삭제 실패: " + e.getMessage());
        }
    }

    // 일정 수정
    @Tool(description = "Google Calendar 일정을 수정합니다. '일정 수정해줘', '~일정 변경해줘' 요청에 사용합니다.")
    public String updateCalendarEvent(
        @ToolParam(description = "수정할 일정 제목") String title,
        @ToolParam(description = "새 제목 (변경 없으면 기존 제목 입력)") String newTitle,
        @ToolParam(description = "새 시작 일시 (ISO 8601, 변경 없으면 null)") String startDatetime,
        @ToolParam(description = "새 종료 일시 (ISO 8601, 변경 없으면 null)") String endDatetime,
        @ToolParam(description = "새 장소 (변경 없으면 null)") String location
    ) {
        String email = getCurrentEmail();
        long start = System.currentTimeMillis();
        try {
            String eventId = calendarService.findEventIdByTitle(email, title);
            if (eventId == null) {
                return "'" + title + "' 일정을 찾을 수 없습니다.";
            }
            calendarService.updateEvent(
                email, eventId, newTitle, startDatetime, endDatetime, location);
            slack.toolExecuted("updateCalendarEvent", email,
                "title=" + title, System.currentTimeMillis() - start);
            return "'" + title + "' 일정을 수정했습니다.";
        } catch (Exception e) {
            slack.toolFailed("updateCalendarEvent", email, e);
            throw new SecretaryAuthException("일정 수정 실패: " + e.getMessage());
        }
    }

    // Slack DM 채널의 메시지를 삭제
    @Tool(description = "Slack DM 채널의 메시지를 삭제합니다. " +
    	    "'Slack 메시지 삭제', '슬랙 메시지 삭제', '슬랙 메세지 삭제', " +
    	    "'Slack 대화 내용 지워줘', '슬랙 대화 지워줘' 요청에 사용합니다. 확인 없이 즉시 실행합니다.")
    	public String deleteSlackMessages() {
    	    String email = getCurrentEmail();
    	    long start = System.currentTimeMillis();
    	    try {
    	        // ✅ SlackBotService의 emailChannelMap에서 channelId 조회
    	        String channelId = slackBotService.getChannelIdByEmail(email);
    	        if (channelId == null || channelId.isBlank()) {
    	            return "Slack 채널 정보가 없습니다. Slack에서 먼저 대화를 시작해 주세요.";
    	        }
    	        new Thread(() -> {
    	            slackBotService.deleteMessages(channelId);
    	            slackBotService.sendMessage(channelId, "Slack 메시지 삭제 완료!");
    	        }).start();
    	        slack.toolExecuted("deleteSlackMessages", email,
    	            "channelId=" + channelId, System.currentTimeMillis() - start);
    	        return "Slack 메시지 삭제를 시작했습니다. 완료되면 Slack으로 알려드립니다.";
    	    } catch (Exception e) {
    	        slack.toolFailed("deleteSlackMessages", email, e);
    	        throw new SecretaryAuthException("Slack 메시지 삭제 실패: " + e.getMessage());
    	    }
    	}

    private String getCurrentEmail() {
        return SecurityContextHolder.getContext()
            .getAuthentication().getName();
    }
}