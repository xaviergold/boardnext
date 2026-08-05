package com.board.dto.board;

import com.board.entity.BoardEntity;
import com.board.entity.LikeEntity;
import com.board.entity.MemberEntity;

import lombok.*;

@Getter
@Setter
public class LikeDTO {

	private BoardEntity seqno;
	private MemberEntity email;
	private String mylikecheck;
	private String mydislikecheck;
	private String likedate;
	private String dislikedate;
	
	public LikeDTO(LikeEntity likeEntity) {
		
		this.seqno = likeEntity.getSeqno();
		this.email = likeEntity.getEmail();
		this.mylikecheck = likeEntity.getMylikecheck();
		this.mydislikecheck = likeEntity.getMydislikecheck();
		this.likedate = likeEntity.getLikedate();
		this.dislikedate = likeEntity.getDislikedate();

	}
	
	public LikeEntity dtoToEntity(LikeDTO dto) {
		
		LikeEntity entity = LikeEntity.builder()
							.email(dto.getEmail())
							.seqno(dto.getSeqno())
							.mylikecheck(dto.getMylikecheck())
							.mydislikecheck(dto.getMydislikecheck())
							.likedate(dto.getLikedate())
							.dislikedate(dto.getDislikedate())
							.build();
		return entity;
	}
	
}
