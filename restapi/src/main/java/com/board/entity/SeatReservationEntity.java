package com.board.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 좌석 예약 확정 내역. "홀드(HOLDING)" 상태는 여기 저장하지 않고 Redis에만 존재한다.
 * 이 테이블에는 실제로 확정(RESERVED)되었거나 관람이 끝난(COMPLETED) 예약만 들어온다.
 *
 * status
 *  - RESERVED  : 예약 확정, 아직 관람 전(좌석 점유 유지)
 *  - COMPLETED : 관람 완료, 좌석 반환됨
 *
 * 동시성 방어: seat_id 는 status='RESERVED' 인 행에 대해서만 유니크해야 하므로
 * jpa_seat_reservation_schema.sql 의 함수 기반 유니크 인덱스로 DB 단에서 최종 방어한다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity(name = "seatReservation")
@Table(name = "jpa_seat_reservation")
public class SeatReservationEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SEAT_RESERVATION_SEQ")
	@SequenceGenerator(name = "SEAT_RESERVATION_SEQ", sequenceName = "jpa_seat_reservation_seq", initialValue = 1, allocationSize = 1)
	@Column(name = "seqno", nullable = false)
	private Long seqno;

	@Column(name = "seat_id", length = 20, nullable = false)
	private String seatId;

	@Column(name = "status", length = 20, nullable = false)
	private String status; // "RESERVED" | "COMPLETED"

	@Column(name = "reservedate", nullable = false)
	private LocalDateTime reservedate;

	@Column(name = "completedate", nullable = true)
	private LocalDateTime completedate;

	// BoardEntity 와 동일하게 회원(email)과 FK 연결. 지연 로딩(Lazy) 사용.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "email", nullable = false)
	private MemberEntity email;
}
