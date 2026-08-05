package com.board.entity.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.board.dto.board.ReplyInterface;
import com.board.entity.ReplyEntity;

public interface ReplyRepository extends JpaRepository<ReplyEntity,Long>{
	@Query(value="select * from jpa_reply where seqno=:seqno order by replyseqno desc",nativeQuery=true)
	List<ReplyInterface> replyView(@Param("seqno") Long seqno);
}
