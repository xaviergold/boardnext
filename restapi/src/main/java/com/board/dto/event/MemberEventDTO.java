package com.board.dto.event;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * member-events 토픽에 실리는 이벤트 페이로드
 * 현재는 SIGNUP만 사용하지만, 추후 다른 회원 이벤트(예: WITHDRAW)가 늘어날 수 있어
 * eventType 필드로 확장 가능하게 설계
 *
 * 비밀번호는 반드시 API 단에서 암호화된 상태로 실어 보낼 것 (평문 절대 금지)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemberEventDTO implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final String SIGNUP = "SIGNUP";

	private String eventType;   // SIGNUP
	private String jobId;       // Job 상태 추적용 ID

	private String email;       // PK
	private String username;
	private String password;    // 암호화된 비밀번호 (BCrypt 인코딩 완료 상태)
	private String gender;
	private String hobby;
	private String job;
	private String description;
	private String zipcode;
	private String address;
	private String telno;
	private String nickname;
	private String role;
	private String org_filename;
	private String stored_filename;
	private Long filesize;
	private LocalDateTime regdate;
	private LocalDateTime lastpwdate;
	private LocalDateTime lastpwcheckdate;
	private String FromSocial;
	private String secretary;

	private LocalDateTime eventTime;
}