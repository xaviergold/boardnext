package com.board.dto.board;

import java.time.LocalDateTime;

import com.board.entity.BoardEntity;
import com.board.entity.MemberEntity;
import com.board.entity.ReplyEntity;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ReplyDTO {

	private Long replyseqno;
	private String replywriter;
	private String replycontent;
	private String email;
	private Long seqno;
	private LocalDateTime replyregdate;
	
	public ReplyDTO(ReplyEntity replyEntity) {
		
		this.replyseqno = replyEntity.getReplyseqno();
		this.replywriter = replyEntity.getReplywriter();
		this.replycontent = replyEntity.getReplycontent();
		this.replyregdate = replyEntity.getReplyregdate();
		this.email = replyEntity.getEmail().getEmail();
		this.seqno = replyEntity.getSeqno().getSeqno();
		
	}
	
}
