package com.board.service.seat;

import java.util.Map;

import com.board.dto.seat.ReserveResponse;
import com.board.dto.seat.SeatStatusInfo;

public interface SeatService {

	// 좌석맵 조회 (Oracle RESERVED + Redis HOLDING 병합). AVAILABLE 좌석은 Map 에 포함하지 않음.
	Map<String, SeatStatusInfo> seatMap();

	// 좌석이 이미 RESERVED 상태인지 확인 (홀드 시도 전 선검증용)
	boolean isReserved(String seatId);

	// 홀드 -> 예약 확정. Redis 홀드 소유자 검증 후 Oracle 에 INSERT.
	ReserveResponse reserve(String seatId, String email);

	// 관람 완료 처리. 본인 예약인지 검증 후 COMPLETED 로 상태 변경 + 좌석 반환 브로드캐스트.
	void completeWatching(Long reservationSeqno, String email);
}
