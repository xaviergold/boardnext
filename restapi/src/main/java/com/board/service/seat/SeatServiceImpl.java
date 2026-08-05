package com.board.service.seat;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.board.dto.seat.ReserveResponse;
import com.board.dto.seat.SeatEvent;
import com.board.dto.seat.SeatStatusInfo;
import com.board.entity.MemberEntity;
import com.board.entity.SeatReservationEntity;
import com.board.entity.repository.MemberRepository;
import com.board.entity.repository.SeatReservationRepository;
import com.board.websocket.SeatEventBroadcastClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SeatServiceImpl implements SeatService {

	private final SeatReservationRepository seatReservationRepository;
	private final MemberRepository memberRepository;
	private final SeatHoldService seatHoldService;
	private final SeatEventBroadcastClient broadcastClient;

	@Override
	public Map<String, SeatStatusInfo> seatMap() {
		Map<String, SeatStatusInfo> result = new HashMap<>();

		// 1) Oracle 의 확정 예약(RESERVED) 반영
		seatReservationRepository.findAllReserved().forEach(r ->
				result.put(r.getSeatId(), SeatStatusInfo.reserved(r.getEmail().getEmail(), r.getSeqno()))
		);

		// 2) Redis 의 임시 점유(HOLDING) 반영 - RESERVED 좌석은 애초에 홀드가 걸릴 수 없으므로 덮어써도 안전
		seatHoldService.findAllHolding().forEach((seatId, holdInfo) ->
				result.put(seatId, SeatStatusInfo.holding(holdInfo.email(), holdInfo.expiresAtEpochMillis()))
		);

		return result;
	}

	@Override
	public boolean isReserved(String seatId) {
		return seatReservationRepository.findActiveBySeatId(seatId).isPresent();
	}

	@Override
	@Transactional
	public ReserveResponse reserve(String seatId, String email) {
		// 1) Redis 홀드 소유자 검증 (본인 홀드가 아니면 여기서 예외 -> 컨트롤러가 409 로 변환)
		seatHoldService.consumeHoldForReservation(seatId, email);

		// 2) DB 유니크 인덱스가 최종 동시성 방어선 -> 저장 시 제약 위반이면 예외 발생, 컨트롤러에서 409 로 변환
		MemberEntity member = memberRepository.findById(email)
				.orElseThrow(() -> new IllegalArgumentException("회원 정보를 찾을 수 없습니다: " + email));

		SeatReservationEntity entity = SeatReservationEntity.builder()
				.seatId(seatId)
				.email(member)
				.status("RESERVED")
				.reservedate(LocalDateTime.now())
				.build();

		SeatReservationEntity saved = seatReservationRepository.save(entity);

		broadcastClient.broadcast(SeatEvent.reserved(seatId, email, saved.getSeqno()));

		return new ReserveResponse(saved.getSeqno(), seatId);
	}

	@Override
	@Transactional
	public void completeWatching(Long reservationSeqno, String email) {
		SeatReservationEntity reservation = seatReservationRepository
				.findMyActiveReservation(reservationSeqno, email)
				.orElseThrow(() -> new IllegalArgumentException("본인의 활성 예약을 찾을 수 없습니다."));

		reservation.setStatus("COMPLETED");
		reservation.setCompletedate(LocalDateTime.now());
		seatReservationRepository.save(reservation);

		broadcastClient.broadcast(SeatEvent.available(reservation.getSeatId()));
	}
}
