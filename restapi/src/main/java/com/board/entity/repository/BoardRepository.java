package com.board.entity.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.board.entity.BoardEntity;

public interface BoardRepository extends JpaRepository<BoardEntity,Long>{

	//게시물 목록 보기
	public Page<BoardEntity> findByWriterContainingOrTitleContainingOrContentContaining(String keyword1, String keyword2, String keyword3, Pageable pageable);
	//select * from jpa_board where writer like '%'||'aaa'||'%' or title like '%'||'aaa'||'%' or content like '%'||'aaa'||'%' 	
	//게시물 이전 보기 - JPQL(Java Persistent Query Language)
	@Query("select max(b.seqno) from board b where b.seqno < :seqno and (b.writer like %:keyword1% or b.title like %:keyword2% or b.content like %:keyword3%)")
	public Long findPreSeqno(@Param("seqno") Long seqno, @Param("keyword1") String keyword1,@Param("keyword2") String keyword2,@Param("keyword3") String keyword3);
	
	//게시물 다음 보기 - JPQL(Java Persistent Query Language)
	@Query("select min(b.seqno) from board b where b.seqno > :seqno and (b.writer like %:keyword1% or b.title like %:keyword2% or b.content like %:keyword3%)")
	public Long findNextSeqno(@Param("seqno") Long seqno, @Param("keyword1") String keyword1,@Param("keyword2") String keyword2,@Param("keyword3") String keyword3); 
	
	//게시물 조회수 증가
	@Transactional
	@Modifying //엔티티 클래스에 적용될수 있도록 함.
	@Query(value="update jpa_board set hitno = (select nvl(hitno,0) from jpa_board where seqno=:seqno) + 1 where seqno=:seqno",nativeQuery=true)
	public void updateHitno(@Param("seqno") Long seqno); 
	
	//max seqno 구하기
	@Query(value="select max(seqno) from jpa_board where email = :email", nativeQuery=true)
	public Long getMaxSeqno(@Param("email") String email);
	
	//[Kafka 비동기 등록용] 게시글 시퀀스 nextval 채번
	//Consumer가 비동기로 INSERT 하기 전에, API 단에서 미리 seqno를 확보해서
	//클라이언트에 즉시 돌려주고(파일 등록 등에 사용), 이벤트에도 실어 보내기 위함
	@Query(value="select jpa_board_seq.nextval from dual", nativeQuery=true)
	public Long getNextSeqno();

	//[Kafka Consumer용] 미리 채번된 seqno로 직접 INSERT
	//@GeneratedValue가 붙은 엔티티에 seqno를 직접 세팅하면 JPA가 INSERT/UPDATE를 혼동하므로
	//네이티브 쿼리로 직접 INSERT해서 시퀀스 재채번 없이 지정된 seqno로 저장
	@Transactional
	@Modifying
	@Query(value = "insert into jpa_board (seqno, email, writer, title, content, regdate, hitno, likecnt, dislikecnt) "
			+ "values (:seqno, :email, :writer, :title, :content, :regdate, 0, 0, 0)",
			nativeQuery = true)
	public void insertWithSeqno(
			@Param("seqno") Long seqno,
			@Param("email") String email,
			@Param("writer") String writer,
			@Param("title") String title,
			@Param("content") String content,
			@Param("regdate") java.time.LocalDateTime regdate);

}