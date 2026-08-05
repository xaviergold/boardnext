package com.board.dto.seat;

/**
 * /topic/seat-events 로 브로드캐스트되는 좌석 상태 변경 이벤트.
 * status 가 "AVAILABLE" 이면 email/reservationSeqno/expiresAt 모두 null.
 */
public record SeatEvent(
		String seatId,
		String status,
		String email,
		Long reservationSeqno,
		Long expiresAtEpochMillis
) {
	public static SeatEvent available(String seatId) {
		return new SeatEvent(seatId, "AVAILABLE", null, null, null);
	}

	public static SeatEvent holding(String seatId, String email, long expiresAtEpochMillis) {
		return new SeatEvent(seatId, "HOLDING", email, null, expiresAtEpochMillis);
	}

	public static SeatEvent reserved(String seatId, String email, Long reservationSeqno) {
		return new SeatEvent(seatId, "RESERVED", email, reservationSeqno, null);
	}
}
