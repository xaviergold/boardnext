package com.board.async;

import java.util.HashMap;
import java.util.Map;

import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.board.dto.event.BoardEventDTO;
import com.board.entity.BoardEntity;
import com.board.entity.repository.BoardRepository;
import com.board.service.board.BoardService;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * board-events 토픽 Consumer
 * eventType(CREATE/DELETE)에 따라 Oracle 반영 및 Redis 캐시 무효화 수행
 *
 * 에러 정책(재시도 3회 + DLT 이동)은 KafkaConsumerConfig에서 전역으로 설정
 * 이 클래스에서는 비즈니스 로직 실패 시 예외를 던지기만 하면 됨 (재시도/DLT는 ErrorHandler가 처리)
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class BoardEventConsumer {

	private final BoardRepository boardRepository;
	private final BoardService boardService; // 기존 deleteFileList() 재사용
	private final JobStatusManager jobStatusManager;
	private final CacheManager cacheManager;

	@KafkaListener(
			topics = BoardKafkaProducer.BOARD_TOPIC,
			groupId = "boardnext-board-events-group",
			containerFactory = "boardEventListenerContainerFactory"
	)
	public void consume(BoardEventDTO event) {
		log.info("[BoardEventConsumer] 이벤트 수신 - type: {}, jobId: {}, seqno: {}",
				event.getEventType(), event.getJobId(), event.getSeqno());

		try {
			switch (event.getEventType()) {
				case BoardEventDTO.CREATE -> handleCreate(event);
				case BoardEventDTO.DELETE -> handleDelete(event);
				default -> log.warn("[BoardEventConsumer] 알 수 없는 eventType: {}", event.getEventType());
			}
		} catch (Exception e) {
			// 여기서 잡은 뒤 다시 던져야 ErrorHandler가 재시도 카운트를 셀 수 있음
			// checked exception일 수 있으므로 RuntimeException으로 감싸서 던짐
			log.error("[BoardEventConsumer] 처리 실패 - jobId: {}, seqno: {}", event.getJobId(), event.getSeqno(), e);
			throw new RuntimeException("board-event 처리 실패: " + event.getEventType(), e);
		}
	}

	private void handleCreate(BoardEventDTO event) {
		// @GeneratedValue가 붙은 엔티티에 seqno를 직접 세팅하면
		// JPA가 시퀀스를 재채번하거나 UPDATE로 혼동하므로 네이티브 쿼리로 직접 INSERT
		boardService.insertWithSeqno(
				event.getSeqno(),
				event.getEmail(),
				event.getWriter(),
				event.getTitle(),
				event.getContent(),
				event.getRegdate()
		);

		log.info("[BoardEventConsumer] 게시글 등록 완료 - seqno: {}", event.getSeqno());
		jobStatusManager.markDone(event.getJobId(), event.getSeqno());
	}

	private void handleDelete(BoardEventDTO event) throws Exception {
		Long seqno = event.getSeqno();

		// 파일 soft delete (checkfile = "N") - 기존 BoardServiceImpl 로직 그대로 재사용
		Map<String, Object> data = new HashMap<>();
		data.put("kind", "B");
		data.put("seqno", seqno);
		boardService.deleteFileList(data);

		// 게시글 삭제
		BoardEntity boardEntity = boardRepository.findById(seqno)
				.orElseThrow(() -> new RuntimeException("게시글 없음: " + seqno));
		boardRepository.delete(boardEntity);

		// Redis 캐시 무효화 - @CacheEvict는 동기 호출에서만 동작하므로
		// Consumer 같은 비동기 컨텍스트에서는 CacheManager로 직접 제거
		var cache = cacheManager.getCache("board");
		if (cache != null) {
			cache.evict(seqno);
		}

		log.info("[BoardEventConsumer] 게시글 삭제 완료 - seqno: {}", seqno);
		jobStatusManager.markDone(event.getJobId());
	}
}