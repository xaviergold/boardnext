package com.board.dto.event;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * board-events 토픽에 실리는 이벤트 페이로드
 * eventType으로 CREATE / DELETE 구분
 *
 * CREATE 시: seqno는 API 단에서 BoardRepository.getNextSeqno()로 미리 채번해서 실어 보냄
 *           (Consumer가 INSERT 시 이 seqno를 그대로 사용)
 * DELETE 시: seqno만 있으면 충분 (파일 정리, 게시글 삭제 모두 seqno 기준)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BoardEventDTO implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final String CREATE = "CREATE";
	public static final String DELETE = "DELETE";

	private String eventType;   // CREATE / DELETE
	private String jobId;       // Job 상태 추적용 ID (Redis 키와 매핑)

	// CREATE 시 사용되는 필드
	private Long seqno;         // 미리 채번된 게시글 번호
	private String email;       // 작성자 email (FK)
	private String writer;
	private String title;
	private String content;
	private LocalDateTime regdate;

	// 공통
	private LocalDateTime eventTime;
}