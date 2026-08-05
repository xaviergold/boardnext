package com.board.entity.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.board.entity.SeatReservationEntity;

public interface SeatReservationRepository extends JpaRepository<SeatReservationEntity, Long> {

	// 현재 RESERVED 상태인 좌석 전체 조회 (좌석맵 응답 구성용)
	@Query("select r from seatReservation r where r.status = 'RESERVED'")
	List<SeatReservationEntity> findAllReserved();

	// 특정 좌석의 현재 RESERVED 예약 조회 (있으면 최대 1건)
	@Query("select r from seatReservation r where r.seatId = :seatId and r.status = 'RESERVED'")
	Optional<SeatReservationEntity> findActiveBySeatId(@Param("seatId") String seatId);

	// 관람 완료 처리 시, 본인 예약이 맞는지 확인하기 위해 email 까지 조건에 포함
	@Query("select r from seatReservation r where r.seqno = :seqno and r.email.email = :email and r.status = 'RESERVED'")
	Optional<SeatReservationEntity> findMyActiveReservation(@Param("seqno") Long seqno, @Param("email") String email);
}
