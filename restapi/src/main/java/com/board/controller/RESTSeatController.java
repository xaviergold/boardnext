package com.board.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.board.dto.seat.HoldResponse;
import com.board.dto.seat.ReserveResponse;
import com.board.dto.seat.SeatStatusInfo;
import com.board.service.seat.SeatHoldService;
import com.board.service.seat.SeatService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;

@RestController
@AllArgsConstructor
@Tag(name = "좌석 예약 관리 API", description = "Redis + WebSocket 기반 실시간 좌석 예약을 위한 REST API 클래스입니다.")
public class RESTSeatController {

	private final SeatService seatService;
	private final SeatHoldService seatHoldService;

	// 좌석맵 조회 (RESERVED + HOLDING 좌석만 포함, 나머지는 프런트에서 AVAILABLE 로 간주)
	@GetMapping("/api/seat/list")
	public ResponseEntity<Map<String, SeatStatusInfo>> getSeatMap() {
		return ResponseEntity.ok().body(seatService.seatMap());
	}

	// 좌석 클릭 -> 3분 임시 점유
	@PostMapping("/api/seat/hold")
	public ResponseEntity<?> hold(@RequestParam("seatId") String seatId, @RequestParam("email") String email) {
		if (seatService.isReserved(seatId)) {
			return ResponseEntity.status(409).body(Map.of("message", "이미 예약이 확정된 좌석입니다."));
		}
		try {
			long expiresAt = seatHoldService.hold(seatId, email);
			return ResponseEntity.ok().body(new HoldResponse(seatId, expiresAt));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
		}
	}

	// 점유 취소 (다른 좌석으로 바꾸거나, 사용자가 선택을 취소한 경우)
	@DeleteMapping("/api/seat/hold")
	public ResponseEntity<?> cancelHold(@RequestParam("seatId") String seatId, @RequestParam("email") String email) {
		try {
			seatHoldService.release(seatId, email);
			return ResponseEntity.ok().build();
		} catch (IllegalStateException e) {
			return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
		}
	}

	// 점유 -> 예약 확정
	@PostMapping("/api/seat/reserve")
	public ResponseEntity<?> reserve(@RequestParam("seatId") String seatId, @RequestParam("email") String email) {
		try {
			ReserveResponse result = seatService.reserve(seatId, email);
			return ResponseEntity.ok().body(result);
		} catch (IllegalStateException e) {
			// 홀드 만료/소유자 불일치
			return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
		} catch (org.springframework.dao.DataIntegrityViolationException e) {
			// DB 유니크 인덱스 위반 - Redis 락을 통과했더라도 걸리는 최종 방어선
			return ResponseEntity.status(409).body(Map.of("message", "다른 사용자가 먼저 예약을 확정했습니다."));
		}
	}

	// 관람 완료 -> 좌석 반환
	@PostMapping("/api/seat/complete")
	public ResponseEntity<?> complete(@RequestParam("reservationSeqno") Long reservationSeqno,
			@RequestParam("email") String email) {
		try {
			seatService.completeWatching(reservationSeqno, email);
			return ResponseEntity.ok().build();
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
		}
	}
}
