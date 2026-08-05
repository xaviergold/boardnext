package com.board.async;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.board.dto.event.BoardEventDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * 게시글 작성/삭제 이벤트를 Kafka로 발행하는 컴포넌트
 * 회원 관련 이벤트는 MemberKafkaProducer에서 담당
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class BoardKafkaProducer {

	public static final String BOARD_TOPIC = "board-events";

	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final JobStatusManager jobStatusManager;

	/**
	 * 게시글 작성 이벤트 발행
	 * seqno는 호출 전에 BoardRepository.getNextSeqno()로 미리 채번해서 넘겨받음
	 * @return 클라이언트 폴링용 jobId
	 */
	public String publishBoardCreate(Long seqno, String email, String writer, String title, String content) {
		String jobId = UUID.randomUUID().toString().replaceAll("-", "");

		BoardEventDTO event = BoardEventDTO.builder()
				.eventType(BoardEventDTO.CREATE)
				.jobId(jobId)
				.seqno(seqno)
				.email(email)
				.writer(writer)
				.title(title)
				.content(content)
				.regdate(LocalDateTime.now())
				.eventTime(LocalDateTime.now())
				.build();

		jobStatusManager.markProcessing(jobId);

		kafkaTemplate.send(BOARD_TOPIC, String.valueOf(seqno), event)
				.whenComplete((result, ex) -> {
					if (ex != null) {
						log.error("[Kafka] 게시글 작성 이벤트 발행 실패 - jobId: {}, seqno: {}", jobId, seqno, ex);
						jobStatusManager.markFailed(jobId, "이벤트 발행에 실패했습니다.");
					} else {
						log.info("[Kafka] 게시글 작성 이벤트 발행 성공 - jobId: {}, seqno: {}", jobId, seqno);
					}
				});

		return jobId;
	}

	/**
	 * 게시글 삭제 이벤트 발행
	 * @return 클라이언트 폴링용 jobId
	 */
	public String publishBoardDelete(Long seqno) {
		String jobId = UUID.randomUUID().toString().replaceAll("-", "");

		BoardEventDTO event = BoardEventDTO.builder()
				.eventType(BoardEventDTO.DELETE)
				.jobId(jobId)
				.seqno(seqno)
				.eventTime(LocalDateTime.now())
				.build();

		jobStatusManager.markProcessing(jobId);

		kafkaTemplate.send(BOARD_TOPIC, String.valueOf(seqno), event)
				.whenComplete((result, ex) -> {
					if (ex != null) {
						log.error("[Kafka] 게시글 삭제 이벤트 발행 실패 - jobId: {}, seqno: {}", jobId, seqno, ex);
						jobStatusManager.markFailed(jobId, "이벤트 발행에 실패했습니다.");
					} else {
						log.info("[Kafka] 게시글 삭제 이벤트 발행 성공 - jobId: {}, seqno: {}", jobId, seqno);
					}
				});

		return jobId;
	}
}