package com.board.async;

/**
 * 비동기(Kafka 경유) 작업의 진행 상태
 * Redis에 job:{jobId} 키로 이 상태값이 저장된다
 */
public enum JobStatus {
	PROCESSING, // Producer가 이벤트 발행 직후 초기 상태
	DONE,       // Consumer가 정상 처리 완료
	FAILED      // Consumer가 재시도(3회) 모두 실패하여 DLT로 이동된 상태
}