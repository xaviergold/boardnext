package com.board.websocket;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.board.dto.seat.SeatEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;

/**
 * 기존 com.board.controller.RESTChatController / 별도 Node ws 서버(4000번 포트, ws-server.js)와
 * 연동하기 위한 컴포넌트. Spring 자체는 STOMP 브로커 역할을 하지 않고, 이 채팅 서버에
 * "클라이언트"로 접속해서 좌석 이벤트를 흘려보내는 역할만 한다.
 *
 * 전송 메시지 형식은 ws-server.js 의 ENTER/TALK 와 동일한 포맷을 따르되, 타입만 "SEAT_EVENT"로 구분.
 * ws-server.js 는 SEAT_EVENT 를 받으면 별도 로직 없이 roomId 로 그대로 브로드캐스트한다 (해당 case 추가 필요).
 *
 * ⚠ chatServerUrl 값(application.yml 의 seat.chat-ws-url)은 실제 /board/chat 프런트가 접속하는
 *   주소와 동일한 host/port 여야 한다. 현재는 ws://localhost:4000 으로 가정했으니,
 *   실제 배포 환경에 맞게 반드시 확인/수정할 것.
 */
@Component
@Log4j2
public class SeatEventBroadcastClient extends TextWebSocketHandler {

	private static final String SEAT_ROOM_ID = "seat";
	private static final long RECONNECT_DELAY_SECONDS = 5L;

	@Value("${seat.chat-ws-url:ws://localhost:4000}")
	//seat.chat-ws-url : 찾을 프로퍼티 키
	//: 뒤의 ws://localhost:4000는 해당 키가 어디에도 정의되어 있지 않을 때만 사용되는 기본값
	private String chatServerUrl;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	private volatile WebSocketSession session;
	private volatile boolean shuttingDown = false;

	@PostConstruct
	public void connect() {
		attemptConnect();
	}

	@PreDestroy
	public void shutdown() {
		shuttingDown = true;
		scheduler.shutdownNow();
		if (session != null && session.isOpen()) {
			try {
				session.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void attemptConnect() {
		if (shuttingDown) return;
		try {
			StandardWebSocketClient client = new StandardWebSocketClient();
			client.execute(this, chatServerUrl)
					.exceptionally(ex -> {
						// client.execute()는 CompletableFuture 를 반환하므로, 연결 자체가 비동기로 실패하는 경우
						// (예: 앱 기동 시점에 채팅 서버가 아직 안 떠 있어 connection refused 등)는
						// 여기서 잡아줘야만 재연결이 스케줄링된다. 이 콜백이 없으면 최초 접속 실패 시
						// afterConnectionClosed/handleTransportError 도 호출되지 않아 영원히 재시도하지 않는다.
						log.warn("좌석 이벤트 브로드캐스트용 채팅 서버 접속 실패 ({}). {}초 후 재시도. 원인: {}",
								chatServerUrl, RECONNECT_DELAY_SECONDS, ex.getMessage());
						scheduleReconnect();
						return null;
					});
		} catch (Exception e) {
			log.warn("좌석 이벤트 브로드캐스트용 채팅 서버 접속 실패 ({}). {}초 후 재시도. 원인: {}",
					chatServerUrl, RECONNECT_DELAY_SECONDS, e.getMessage());
			scheduleReconnect();
		}
	}

	private void scheduleReconnect() {
		if (shuttingDown) return;
		scheduler.schedule(this::attemptConnect, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		this.session = session;
		log.info("좌석 이벤트 브로드캐스트용 채팅 서버 연결 성공: {}", chatServerUrl);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		this.session = null;
		log.warn("채팅 서버 연결 종료됨 ({}). 재연결을 시도합니다.", status);
		scheduleReconnect();
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		log.warn("채팅 서버 연결 오류", exception);
	}

	/** 좌석 상태 변경 이벤트를 "seat" 방으로 브로드캐스트. 연결이 끊긴 상태면 조용히 로그만 남기고 무시(재연결 대기 중). */
	public void broadcast(SeatEvent event) {
		WebSocketSession currentSession = this.session;
		if (currentSession == null || !currentSession.isOpen()) {
			log.warn("채팅 서버 연결이 없어 좌석 이벤트를 브로드캐스트하지 못했습니다: {}", event);
			return;
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("type", "SEAT_EVENT");
		payload.put("roomId", SEAT_ROOM_ID);
		payload.put("sender", "server");
		payload.put("seatId", event.seatId());
		payload.put("status", event.status());
		payload.put("email", event.email());
		payload.put("reservationSeqno", event.reservationSeqno());
		payload.put("expiresAtEpochMillis", event.expiresAtEpochMillis());

		try {
			currentSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
		} catch (IOException e) {
			log.error("좌석 이벤트 전송 실패: {}", event, e);
		}
	}
}