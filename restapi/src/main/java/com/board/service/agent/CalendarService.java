package com.board.service.agent;

import com.board.dto.agent.EventDto;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.*;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class CalendarService {

    private final GoogleTokenService googleTokenService;
    private final SlackNotifier slack;

    // Calendar 클라이언트 생성
    private Calendar getCalendarClient(String email) throws Exception {
        String accessToken = googleTokenService.getValidAccessToken(email);

        GoogleCredentials credentials = GoogleCredentials
            .create(new AccessToken(accessToken, null));

        return new Calendar.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            new HttpCredentialsAdapter(credentials))
            .setApplicationName("boardnext-secretary")
            .build();
    }

    // 오늘 또는 이번 주 일정 조회
    public List<EventDto> listEvents(String email, String period) throws Exception {
        Calendar calendar = getCalendarClient(email);

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));

        // 오늘 00:00부터 조회 (지난 일정도 포함)
        ZonedDateTime todayStart = now.toLocalDate()
            .atStartOfDay(ZoneId.of("Asia/Seoul"));

        ZonedDateTime todayEnd = period.equals("THIS_WEEK")
            ? now.with(DayOfWeek.SUNDAY).withHour(23).withMinute(59)
            : now.toLocalDate().atTime(23, 59).atZone(ZoneId.of("Asia/Seoul"));

        log.info("일정 조회 시작: {} ~ {}", todayStart, todayEnd);

        Events events = calendar.events().list("primary")
            .setTimeMin(new DateTime(todayStart.toInstant().toEpochMilli()))
            .setTimeMax(new DateTime(todayEnd.toInstant().toEpochMilli()))
            .setOrderBy("startTime")
            .setSingleEvents(true)
            .execute();

        log.info("가져온 일정 수: {}", events.getItems() == null ? 0 : events.getItems().size());

        if (events.getItems() == null) return List.of();

        return events.getItems().stream()
            .map(e -> EventDto.builder()
                .id(e.getId())
                .title(e.getSummary())
                .start(e.getStart().getDateTime() != null
                    ? e.getStart().getDateTime().toString()
                    : e.getStart().getDate().toString())
                .end(e.getEnd().getDateTime() != null
                    ? e.getEnd().getDateTime().toString()
                    : e.getEnd().getDate().toString())
                .location(e.getLocation())
                .htmlLink(e.getHtmlLink())
                .build())
            .collect(Collectors.toList());
    }

    // 일정 생성
    public EventDto createEvent(String email, String title,
            String startDatetime, String endDatetime, String location) throws Exception {
        Calendar calendar = getCalendarClient(email);

        Event event = new Event().setSummary(title);

        // 한국 시간(+09:00) 명시적으로 추가
        // startDatetime이 "2026-06-22T09:00:00" 형식으로 오면
        // "+09:00" 붙여서 "2026-06-22T09:00:00+09:00"으로 변환
        String start = ensureKoreanTimezone(startDatetime);
        String end   = ensureKoreanTimezone(endDatetime);

        event.setStart(new EventDateTime()
            .setDateTime(new DateTime(start))
            .setTimeZone("Asia/Seoul"));
        event.setEnd(new EventDateTime()
            .setDateTime(new DateTime(end))
            .setTimeZone("Asia/Seoul"));

        if (location != null && !location.isEmpty()) {
            event.setLocation(location);
        }

        Event created = calendar.events().insert("primary", event).execute();

        return EventDto.builder()
            .id(created.getId())
            .title(created.getSummary())
            .start(startDatetime)
            .end(endDatetime)
            .location(location)
            .htmlLink(created.getHtmlLink())
            .build();
    }

    // 시간대 없으면 한국 시간(+09:00) 추가
    private String ensureKoreanTimezone(String datetime) {
        if (datetime == null) return null;
        // 이미 시간대 정보가 있으면 그대로
        if (datetime.contains("+") || datetime.contains("Z")) return datetime;
        // 없으면 한국 시간 추가
        return datetime + "+09:00";
    }

    // 일정 삭제 (휴지통으로)
    public void deleteEvent(String email, String eventId) throws Exception {
        Calendar calendar = getCalendarClient(email);
        calendar.events().delete("primary", eventId).execute();
    }

    // 일정 수정
    public EventDto updateEvent(String email, String eventId, String title,
            String startDatetime, String endDatetime, String location) throws Exception {
        Calendar calendar = getCalendarClient(email);

        Event event = calendar.events().get("primary", eventId).execute();

        if (title != null && !title.isEmpty())
            event.setSummary(title);
        if (location != null && !location.isEmpty())
            event.setLocation(location);
        if (startDatetime != null && !startDatetime.isEmpty()) {
            event.setStart(new EventDateTime()
                .setDateTime(new DateTime(startDatetime))
                .setTimeZone("Asia/Seoul"));
        }
        if (endDatetime != null && !endDatetime.isEmpty()) {
            event.setEnd(new EventDateTime()
                .setDateTime(new DateTime(endDatetime))
                .setTimeZone("Asia/Seoul"));
        }

        Event updated = calendar.events()
            .update("primary", eventId, event).execute();

        return EventDto.builder()
            .id(updated.getId())
            .title(updated.getSummary())
            .start(startDatetime)
            .end(endDatetime)
            .location(location)
            .htmlLink(updated.getHtmlLink())
            .build();
    }

    // 일정명으로 eventId 조회 (30일 이내)
    public String findEventIdByTitle(String email, String title) throws Exception {
        Calendar calendar = getCalendarClient(email);

        ZonedDateTime todayStart = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
            .toLocalDate().atStartOfDay(ZoneId.of("Asia/Seoul"));
        ZonedDateTime todayEnd = todayStart.plusDays(30);

        Events events = calendar.events().list("primary")
            .setQ(title)
            .setTimeMin(new DateTime(todayStart.toInstant().toEpochMilli()))
            .setTimeMax(new DateTime(todayEnd.toInstant().toEpochMilli()))
            .setSingleEvents(true)
            .execute();

        if (events.getItems() == null || events.getItems().isEmpty()) return null;
        return events.getItems().get(0).getId();
    }
}