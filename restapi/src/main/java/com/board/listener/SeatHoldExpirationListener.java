package com.board.listener;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import com.board.dto.seat.SeatEvent;
import com.board.websocket.SeatEventBroadcastClient;

import lombok.RequiredArgsConstructor;

/**
 * hold:{seatId} 키가 TTL(3분) 만료로 Redis 에서 자동 삭제될 때 발생하는
 * "__keyevent@N__:expired" 이벤트를 구독해서, 해당 좌석을 AVAILABLE 로 브로드캐스트한다.
 *
 * 이게 없으면: 홀드가 만료돼도 Redis 키만 조용히 사라질 뿐,
 * 이미 화면을 띄워놓고 있는 다른 사용자들에게는 "선택 불가" 상태가 그대로 남아있게 된다.
 */
@Component
@RequiredArgsConstructor
public class SeatHoldExpirationListener implements MessageListener {

	private static final String HOLD_KEY_PREFIX = "hold:";

	private final SeatEventBroadcastClient broadcastClient;

	@Override
	public void onMessage(Message message, byte[] pattern) {
		String expiredKey = message.getBody() != null ? new String(message.getBody()) : null;
		if (expiredKey == null || !expiredKey.startsWith(HOLD_KEY_PREFIX)) {
			return; // 이 프로젝트의 다른 기능이 사용하는 TTL 키의 만료일 수도 있으므로 접두사로 필터링
		}

		String seatId = expiredKey.substring(HOLD_KEY_PREFIX.length());
		broadcastClient.broadcast(SeatEvent.available(seatId));
	}
}