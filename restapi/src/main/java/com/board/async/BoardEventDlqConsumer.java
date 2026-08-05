package com.board.async;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.board.dto.event.BoardEventDTO;
import com.board.entity.FileEntity;
import com.board.entity.repository.FileRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * board-events.DLT (Dead Letter Topic) 전용 Consumer
 *
 * BoardEventConsumer가 재시도 3회를 모두 소진하고 실패한 이벤트가 여기로 들어온다.
 * 특히 CREATE 이벤트가 끝내 실패했다면, 이미 동기로 디스크/DB에 저장해둔 파일이
 * 영원히 연결될 게시글이 없는 "고아 파일"로 남게 되므로 이 시점에 즉시 정리한다.
 *
 * 이 Consumer 자체는 별도의 재시도 정책을 적용하지 않는다 (이미 DLT까지 온 메시지이므로
 * 여기서도 실패하면 그냥 로그만 남기고 넘어감 - 무한 루프 방지)
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class BoardEventDlqConsumer {

	private final FileRepository fileRepository;
	private final JobStatusManager jobStatusManager;

	@KafkaListener(
			topics = BoardKafkaProducer.BOARD_TOPIC + ".DLT",
			groupId = "boardnext-board-events-dlt-group",
			containerFactory = "boardDlqListenerContainerFactory"
	)
	public void consumeDlt(BoardEventDTO event,
			@Header(KafkaHeaders.EXCEPTION_MESSAGE) String exceptionMessage,
			ConsumerRecord<?, ?> record) {

		log.error("[DLT] board-events 최종 실패 - type: {}, jobId: {}, seqno: {}, 사유: {}",
				event.getEventType(), event.getJobId(), event.getSeqno(), exceptionMessage);

		try {
			// Job 상태를 FAILED로 최종 확정 - 클라이언트 폴링이 여기서 멈춤
			jobStatusManager.markFailed(event.getJobId(), "처리에 실패했습니다. 잠시 후 다시 시도해주세요.");

			if (BoardEventDTO.CREATE.equals(event.getEventType())) {
				cleanupOrphanFiles(event.getSeqno());
			}
			// DELETE 실패의 경우 별도 정리 불필요 - 게시글이 그대로 남아있을 뿐이므로
			// 사용자가 재시도하면 됨 (파일이 추가로 생성되는 것도 아님)

		} catch (Exception e) {
			// DLT 핸들러 자체가 실패해도 더 이상 재시도하지 않음 - 여기서 막힌다
			log.error("[DLT] 후처리(파일 정리) 중 예외 발생 - jobId: {}, seqno: {}", event.getJobId(), event.getSeqno(), e);
		}
	}

	/** CREATE 이벤트 최종 실패 시, 이미 동기로 저장된 고아 파일들을 즉시 삭제 */
	private void cleanupOrphanFiles(Long seqno) {
		if (seqno == null) return;

		List<FileEntity> orphanCandidates = fileRepository.findBySeqno(seqno);
		if (orphanCandidates.isEmpty()) {
			return;
		}

		String os = System.getProperty("os.name").toLowerCase();
		String path = os.contains("win") ? "c:\\Repository\\file\\" : "/var/opt/Repository/file/";

		for (FileEntity file : orphanCandidates) {
			try {
				java.io.File diskFile = new java.io.File(path + file.getStored_filename());
				if (diskFile.exists()) {
					diskFile.delete();
				}
				fileRepository.delete(file);
				log.info("[DLT] 고아 파일 즉시 정리 완료 - fileseqno: {}, seqno: {}", file.getFileseqno(), seqno);
			} catch (Exception e) {
				// 파일 하나 정리 실패해도 나머지는 계속 처리 - 여기서 멈추면 안 됨
				log.error("[DLT] 고아 파일 정리 실패 - fileseqno: {}", file.getFileseqno(), e);
			}
		}
	}
}