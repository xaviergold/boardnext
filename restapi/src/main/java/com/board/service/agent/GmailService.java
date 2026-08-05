package com.board.service.agent;

import com.board.dto.agent.MailDto;
import com.google.api.services.gmail.model.MessagePart;
import java.util.Base64;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.*;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class GmailService {

    private final GoogleTokenService googleTokenService;
    private final SlackNotifier slack;

    // Gmail 클라이언트 생성
    private Gmail getGmailClient(String email) throws Exception {
        String accessToken = googleTokenService.getValidAccessToken(email);
        GoogleCredentials credentials = GoogleCredentials
            .create(new AccessToken(accessToken, null));
        return new Gmail.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            new HttpCredentialsAdapter(credentials))
            .setApplicationName("boardnext-secretary")
            .build();
    }

    // 오늘 받은 메일 목록 조회 (count 없는 버전)
    public List<MailDto> getRecentMails(String email) throws Exception {
        return getRecentMails(email, 50);
    }

    // 오늘 받은 메일 목록 조회 (count 있는 버전)
    public List<MailDto> getRecentMails(String email, int count) throws Exception {
        Gmail gmail = getGmailClient(email);

        // 한국 시간 기준 오늘 00:00:00의 epoch milliseconds
        long todayStartEpoch = LocalDate.now(ZoneId.of("Asia/Seoul"))
            .atStartOfDay(ZoneId.of("Asia/Seoul"))
            .toInstant()
            .toEpochMilli();

        //log.info("[Gmail] 오늘 시작 epoch: {}", todayStartEpoch);

        ListMessagesResponse listResponse = gmail.users().messages()
            .list("me")
            .setMaxResults((long) count)
            .setQ("in:inbox")
            .execute();

        if (listResponse.getMessages() == null) return List.of();

        List<MailDto> mails = new ArrayList<>();
        for (Message message : listResponse.getMessages()) {
            // 목록 조회는 metadata만 (빠른 응답) - 본문은 getMailBody()로 별도 조회
            Message detail = gmail.users().messages()
                .get("me", message.getId())
                .setFormat("metadata")
                .setMetadataHeaders(List.of("From", "Subject", "Date"))
                .execute();
            MailDto dto = parseMessage(detail);
            //log.info("[Gmail] 메일: {} | internalDate: {} | 오늘이후: {}",
                //dto.getSubject(), dto.getInternalDate(),
                //dto.getInternalDate() != null && dto.getInternalDate() >= todayStartEpoch);
            mails.add(dto);
        }

        return mails.stream()
            .filter(m -> m.getFrom() != null && !m.getFrom().isEmpty())
            .filter(m -> m.getInternalDate() != null &&
                         m.getInternalDate() >= todayStartEpoch)
            .collect(Collectors.toList());
    }

    // 메일 발송 (CC 포함)
    public void sendMail(String email, String to, String subject, String body,
                         String cc) throws Exception {
        Gmail gmail = getGmailClient(email);
        MimeMessage mimeMessage = createMimeMessage(to, subject, body, cc);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);
        Message message = new Message();
        message.setRaw(Base64.getUrlEncoder().encodeToString(buffer.toByteArray()));
        gmail.users().messages().send("me", message).execute();
        log.info("[Gmail] 메일 발송 완료: to={}, cc={}", to, cc);
    }

    // 임시보관함 저장 (CC 포함)
    public void saveDraft(String email, String to, String subject, String body,
                          String cc) throws Exception {
        Gmail gmail = getGmailClient(email);
        MimeMessage mimeMessage = createMimeMessage(to, subject, body, cc);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);
        Message message = new Message();
        message.setRaw(Base64.getUrlEncoder().encodeToString(buffer.toByteArray()));
        Draft draft = new Draft();
        draft.setMessage(message);
        gmail.users().drafts().create("me", draft).execute();
        log.info("[Gmail] 임시보관함 저장 완료: to={}", to);
    }

    // 메일 답장 (CC 포함)
    public void replyMail(String email, String messageId, String body,
                          String cc) throws Exception {
        Gmail gmail = getGmailClient(email);

        Message original = gmail.users().messages()
            .get("me", messageId)
            .setFormat("metadata")
            .setMetadataHeaders(List.of("From", "Subject", "Message-Id", "References"))
            .execute();

        String from            = "";
        String subject         = "";
        String messageIdHeader = "";
        String references      = "";

        if (original.getPayload() != null && original.getPayload().getHeaders() != null) {
            for (MessagePartHeader header : original.getPayload().getHeaders()) {
                switch (header.getName().toLowerCase()) {
                    case "from"       -> from            = header.getValue();
                    case "subject"    -> subject         = header.getValue();
                    case "message-id" -> messageIdHeader = header.getValue();
                    case "references" -> references      = header.getValue();
                }
            }
        }

        if (!subject.toLowerCase().startsWith("re:")) {
            subject = "Re: " + subject;
        }

        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props, null);
        MimeMessage mimeMessage = new MimeMessage(session);
        mimeMessage.addRecipient(
            jakarta.mail.Message.RecipientType.TO, new InternetAddress(from));
        mimeMessage.setSubject(subject, "UTF-8");
        mimeMessage.setText(body, "UTF-8");
        mimeMessage.setHeader("In-Reply-To", messageIdHeader);
        mimeMessage.setHeader("References",
            references.isEmpty() ? messageIdHeader : references + " " + messageIdHeader);

        if (cc != null && !cc.isBlank()) {
            for (String ccAddr : cc.split(",")) {
                mimeMessage.addRecipient(
                    jakarta.mail.Message.RecipientType.CC,
                    new InternetAddress(ccAddr.trim()));
            }
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);

        Message reply = new Message();
        reply.setRaw(Base64.getUrlEncoder().encodeToString(buffer.toByteArray()));
        reply.setThreadId(original.getThreadId());

        gmail.users().messages().send("me", reply).execute();
        log.info("[Gmail] 답장 발송 완료: to={}, cc={}", from, cc);
    }

    // 전체 답장 (Reply All)
    public void replyAllMail(String email, String messageId, String body) throws Exception {
        Gmail gmail = getGmailClient(email);

        Message original = gmail.users().messages()
            .get("me", messageId)
            .setFormat("metadata")
            .setMetadataHeaders(List.of("From", "To", "Cc", "Subject", "Message-Id", "References"))
            .execute();

        String from            = "";
        String to              = "";
        String origCc          = "";
        String subject         = "";
        String messageIdHeader = "";
        String references      = "";

        if (original.getPayload() != null && original.getPayload().getHeaders() != null) {
            for (MessagePartHeader header : original.getPayload().getHeaders()) {
                switch (header.getName().toLowerCase()) {
                    case "from"       -> from            = header.getValue();
                    case "to"         -> to              = header.getValue();
                    case "cc"         -> origCc          = header.getValue();
                    case "subject"    -> subject         = header.getValue();
                    case "message-id" -> messageIdHeader = header.getValue();
                    case "references" -> references      = header.getValue();
                }
            }
        }

        if (!subject.toLowerCase().startsWith("re:")) {
            subject = "Re: " + subject;
        }

        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props, null);
        MimeMessage mimeMessage = new MimeMessage(session);
        mimeMessage.addRecipient(
            jakarta.mail.Message.RecipientType.TO, new InternetAddress(from));

        // 원본 TO 수신자들도 CC로 추가
        if (!to.isBlank()) {
            for (String addr : to.split(",")) {
                mimeMessage.addRecipient(
                    jakarta.mail.Message.RecipientType.CC,
                    new InternetAddress(addr.trim()));
            }
        }
        // 원본 CC 수신자들도 CC로 추가
        if (!origCc.isBlank()) {
            for (String addr : origCc.split(",")) {
                mimeMessage.addRecipient(
                    jakarta.mail.Message.RecipientType.CC,
                    new InternetAddress(addr.trim()));
            }
        }

        mimeMessage.setSubject(subject, "UTF-8");
        mimeMessage.setText(body, "UTF-8");
        mimeMessage.setHeader("In-Reply-To", messageIdHeader);
        mimeMessage.setHeader("References",
            references.isEmpty() ? messageIdHeader : references + " " + messageIdHeader);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);

        Message reply = new Message();
        reply.setRaw(Base64.getUrlEncoder().encodeToString(buffer.toByteArray()));
        reply.setThreadId(original.getThreadId());

        gmail.users().messages().send("me", reply).execute();
        log.info("[Gmail] 전체 답장 발송 완료: to={}", from);
    }

    // 메일 전달 (CC 포함)
    public void forwardMail(String email, String messageId, String to,
                            String body, String cc) throws Exception {
        Gmail gmail = getGmailClient(email);

        Message original = gmail.users().messages()
            .get("me", messageId)
            .setFormat("metadata")
            .setMetadataHeaders(List.of("From", "Subject", "Date"))
            .execute();

        String origFrom    = "";
        String origSubject = "";
        String origDate    = "";

        if (original.getPayload() != null && original.getPayload().getHeaders() != null) {
            for (MessagePartHeader header : original.getPayload().getHeaders()) {
                switch (header.getName().toLowerCase()) {
                    case "from"    -> origFrom    = header.getValue();
                    case "subject" -> origSubject = header.getValue();
                    case "date"    -> origDate    = header.getValue();
                }
            }
        }

        String fwdSubject = origSubject.toLowerCase().startsWith("fwd:")
            ? origSubject : "Fwd: " + origSubject;

        String fwdBody = body + "\n\n---------- Forwarded message ----------\n" +
            "From: " + origFrom + "\n" +
            "Date: " + origDate + "\n" +
            "Subject: " + origSubject;

        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props, null);
        MimeMessage mimeMessage = new MimeMessage(session);
        mimeMessage.addRecipient(
            jakarta.mail.Message.RecipientType.TO, new InternetAddress(to));
        mimeMessage.setSubject(fwdSubject, "UTF-8");
        mimeMessage.setText(fwdBody, "UTF-8");

        if (cc != null && !cc.isBlank()) {
            for (String ccAddr : cc.split(",")) {
                mimeMessage.addRecipient(
                    jakarta.mail.Message.RecipientType.CC,
                    new InternetAddress(ccAddr.trim()));
            }
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);

        Message fwd = new Message();
        fwd.setRaw(Base64.getUrlEncoder().encodeToString(buffer.toByteArray()));

        gmail.users().messages().send("me", fwd).execute();
        log.info("[Gmail] 전달 완료: to={}, cc={}", to, cc);
    }

    // 메일 삭제 (휴지통)
    public void deleteMail(String email, String messageId) throws Exception {
        Gmail gmail = getGmailClient(email);
        gmail.users().messages().trash("me", messageId).execute();
        log.info("[Gmail] 메일 삭제(휴지통): messageId={}", messageId);
    }

    // 제목으로 메일 검색 (단건)
    public MailDto findMailBySubject(String email, String subject) throws Exception {
        Gmail gmail = getGmailClient(email);
        ListMessagesResponse listResponse = gmail.users().messages()
            .list("me")
            .setQ("subject:" + subject)
            .setMaxResults(1L)
            .execute();

        if (listResponse.getMessages() == null || listResponse.getMessages().isEmpty()) {
            return null;
        }

        Message detail = gmail.users().messages()
            .get("me", listResponse.getMessages().get(0).getId())
            .setFormat("full")  // metadata → full (본문 포함)
            .execute();

        return parseMessage(detail);
    }

    // MimeMessage 생성 (CC 포함)
    private MimeMessage createMimeMessage(String to, String subject, String body,
                                          String cc) throws Exception {
        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props, null);
        MimeMessage mimeMessage = new MimeMessage(session);
        mimeMessage.addRecipient(
            jakarta.mail.Message.RecipientType.TO, new InternetAddress(to));
        mimeMessage.setSubject(subject, "UTF-8");
        mimeMessage.setText(body, "UTF-8");

        if (cc != null && !cc.isBlank()) {
            for (String ccAddr : cc.split(",")) {
                mimeMessage.addRecipient(
                    jakarta.mail.Message.RecipientType.CC,
                    new InternetAddress(ccAddr.trim()));
            }
        }
        return mimeMessage;
    }

    // Message 파싱
    private MailDto parseMessage(Message message) {
        String from    = "";
        String subject = "";
        String date    = "";

        if (message.getPayload() != null && message.getPayload().getHeaders() != null) {
            for (MessagePartHeader header : message.getPayload().getHeaders()) {
                switch (header.getName()) {
                    case "From"    -> from    = header.getValue();
                    case "Subject" -> subject = header.getValue();
                    case "Date"    -> date    = header.getValue();
                }
            }
        }

        // ── 본문 추출 ─────────────────────────────────────────
        String body = extractBody(message.getPayload());

        return MailDto.builder()
            .id(message.getId())
            .from(from)
            .subject(subject)
            .date(date)
            .snippet(message.getSnippet())
            .body(body)
            .internalDate(message.getInternalDate())
            .build();
    }

    /**
     * MessagePart에서 텍스트 본문 추출
     * text/plain → text/html 순으로 시도
     */
    private String extractBody(MessagePart payload) {
        if (payload == null) return "";

        // 단일 파트인 경우
        if (payload.getBody() != null && payload.getBody().getData() != null) {
            String decoded = decodeBase64(payload.getBody().getData());
            return decoded; // HTML 원본 그대로 반환 (프론트에서 렌더링)
        }

        // 멀티파트인 경우 - text/plain 먼저, 없으면 text/html
        if (payload.getParts() != null) {
            String plainText = "";
            String htmlText  = "";
            for (MessagePart part : payload.getParts()) {
                String mimeType = part.getMimeType();
                if ("text/plain".equals(mimeType) && part.getBody() != null
                        && part.getBody().getData() != null) {
                    plainText = decodeBase64(part.getBody().getData());
                } else if ("text/html".equals(mimeType) && part.getBody() != null
                        && part.getBody().getData() != null) {
                    htmlText = decodeBase64(part.getBody().getData());
                } else if (part.getParts() != null) {
                    // 중첩 멀티파트 재귀 탐색
                    String nested = extractBody(part);
                    if (!nested.isEmpty()) {
                        if (plainText.isEmpty()) plainText = nested;
                    }
                }
            }
            // HTML 우선 반환 (프론트에서 렌더링) → 없으면 plainText
            if (!htmlText.isEmpty()) return htmlText;
            if (!plainText.isEmpty()) return plainText;
        }
        return "";
    }

    /**
     * HTML → 읽기 쉬운 텍스트 변환
     * 단순 태그 제거보다 구조를 유지하여 가독성 향상
     */
    private String cleanHtml(String html) {
        if (html == null || html.isBlank()) return "";
        return html
            .replaceAll("(?i)<br\\s*/?>", "\n")        // <br> → 줄바꿈
            .replaceAll("(?i)<p[^>]*>", "\n")           // <p> → 줄바꿈
            .replaceAll("(?i)</p>", "\n")
            .replaceAll("(?i)<div[^>]*>", "\n")         // <div> → 줄바꿈
            .replaceAll("(?i)</div>", "")
            .replaceAll("(?i)<li[^>]*>", "\n- ")        // <li> → 목록
            .replaceAll("(?i)<td[^>]*>", " | ")          // <td> → 구분
            .replaceAll("(?i)<tr[^>]*>", "\n")          // <tr> → 줄바꿈
            .replaceAll("(?i)<style[^>]*>[\\s\\S]*?</style>", "")  // style 제거
            .replaceAll("(?i)<script[^>]*>[\\s\\S]*?</script>", "") // script 제거
            .replaceAll("<[^>]+>", "")                   // 나머지 태그 제거
            .replaceAll("&nbsp;", " ")                   // HTML 엔티티
            .replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("&quot;", "\\")
            .replaceAll("\n{3,}", "\n\n")             // 연속 빈줄 제거
            .trim();
    }

    private String decodeBase64(String data) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(data);
            return new String(decoded, "UTF-8").trim();
        } catch (Exception e) {
            log.warn("[Gmail] Base64 디코딩 실패: {}", e.getMessage());
            return "";
        }
    }
}