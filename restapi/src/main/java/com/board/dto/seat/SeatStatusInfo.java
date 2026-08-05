package com.board.dto.seat;

/**
 * 좌석 하나의 현재 상태를 표현.
 * status : "AVAILABLE" | "HOLDING" | "RESERVED"  (AVAILABLE 인 좌석은 응답 Map에 아예 포함되지 않음 -> 프런트에서 default 처리)
 * email  : HOLDING/RESERVED 인 경우 점유자 이메일. AVAILABLE 이면 응답에 없으므로 null 없음.
 */
public record SeatStatusInfo(
		String status,
		String email,
		Long reservationSeqno,          // RESERVED 인 경우만 값 존재 (관람완료 API 호출 시 필요)
		Long expiresAtEpochMillis       // HOLDING 인 경우만 값 존재 (프런트 카운트다운용)
) {
	public static SeatStatusInfo holding(String email, long expiresAtEpochMillis) {
		return new SeatStatusInfo("HOLDING", email, null, expiresAtEpochMillis);
	}

	public static SeatStatusInfo reserved(String email, Long reservationSeqno) {
		return new SeatStatusInfo("RESERVED", email, reservationSeqno, null);
	}
}
