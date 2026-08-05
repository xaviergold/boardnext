package com.board.service.seat;

import java.time.Duration;
import java.time.Instant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.board.dto.seat.SeatEvent;
import com.board.websocket.SeatEventBroadcastClient;

import lombok.RequiredArgsConstructor;

/**
 * 좌석 임시 점유(HOLDING) 전담 서비스.
 * Redis 를 진실의 원천으로 사용하며, DB 는 전혀 건드리지 않는다.
 * key   : hold:{seatId}
 * value : 점유자 email
 * TTL   : HOLD_SECONDS (3분) -> 만료 시 SeatHoldExpirationListener 가 감지해서 AVAILABLE 브로드캐스트
 */
@Service
@RequiredArgsConstructor
public class SeatHoldService {

	public static final long HOLD_SECONDS = 180L;
	private static final String HOLD_KEY_PREFIX = "hold:";

	private final StringRedisTemplate redisTemplate;
	private final SeatEventBroadcastClient broadcastClient;

	/**
	 * 좌석 점유 시도. 이미 다른 사람이 점유했거나 예약된 좌석이면 실패.
	 * 예약(RESERVED) 여부는 이 서비스가 모르므로, 호출 전에 SeatService.isReserved(seatId) 로 먼저 걸러줘야 한다.
	 */
	public long hold(String seatId, String email) {
		String key = holdKey(seatId);
		Boolean acquired = redisTemplate.opsForValue()
				.setIfAbsent(key, email, Duration.ofSeconds(HOLD_SECONDS));

		if (!Boolean.TRUE.equals(acquired)) {
			throw new IllegalStateException("이미 다른 사용자가 선택 중인 좌석입니다.");
		}

		long expiresAt = Instant.now().plusSeconds(HOLD_SECONDS).toEpochMilli();
		broadcastClient.broadcast(SeatEvent.holding(seatId, email, expiresAt));
		return expiresAt;
	}

	/** 본인이 점유 취소(다른 좌석 클릭 등으로 자발적으로 풀 때) */
	public void release(String seatId, String email) {
		String key = holdKey(seatId);
		String owner = redisTemplate.opsForValue().get(key);

		if (owner == null) {
			return; // 이미 만료되었거나 없음 -> 조용히 무시
		}
		if (!owner.equals(email)) {
			throw new IllegalStateException("본인이 점유한 좌석이 아닙니다.");
		}

		redisTemplate.delete(key);
		broadcastClient.broadcast(SeatEvent.available(seatId));
	}

	/** 예약 확정(SeatService.reserve) 시 호출 - 소유자 검증 후 홀드 해제. 소유자면 email 반환, 아니면 예외 */
	public String consumeHoldForReservation(String seatId, String email) {
		String key = holdKey(seatId);
		String owner = redisTemplate.opsForValue().get(key);

		if (owner == null) {
			throw new IllegalStateException("점유 시간이 만료되었거나 점유하지 않은 좌석입니다. 다시 선택해주세요.");
		}
		if (!owner.equals(email)) {
			throw new IllegalStateException("본인이 점유한 좌석이 아닙니다.");
		}

		redisTemplate.delete(key);
		return owner;
	}

	/** 현재 HOLDING 중인 모든 좌석 조회 (좌석맵 GET 응답 구성용). 데모 규모 기준 KEYS 사용 - 운영 규모라면 SCAN 으로 교체 */
	public java.util.Map<String, HoldInfo> findAllHolding() {
		java.util.Map<String, HoldInfo> result = new java.util.HashMap<>();
		var keys = redisTemplate.keys(HOLD_KEY_PREFIX + "*");
		if (keys == null) return result;

		for (String key : keys) {
			String email = redisTemplate.opsForValue().get(key);
			Long ttlSeconds = redisTemplate.getExpire(key);
			if (email == null || ttlSeconds == null || ttlSeconds < 0) continue;

			String seatId = key.substring(HOLD_KEY_PREFIX.length());
			long expiresAt = Instant.now().plusSeconds(ttlSeconds).toEpochMilli();
			result.put(seatId, new HoldInfo(email, expiresAt));
		}
		return result;
	}

	private String holdKey(String seatId) {
		return HOLD_KEY_PREFIX + seatId;
	}

	public record HoldInfo(String email, long expiresAtEpochMillis) {
	}
}
