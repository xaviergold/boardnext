package com.board.dto.board;

import com.board.entity.FileEntity;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileDTO {
	
	private Long fileseqno;
	private Long seqno;
	private String email;
	private String org_filename;
	private String stored_filename;
	private Long filesize;
	private String checkfile;
	
	public FileDTO(FileEntity fileEntity) {
		
		this.fileseqno = fileEntity.getFileseqno();
		this.seqno = fileEntity.getSeqno();
		this.email = fileEntity.getEmail();
		this.org_filename = fileEntity.getOrg_filename();
		this.stored_filename = fileEntity.getStored_filename();
		this.filesize = fileEntity.getFilesize();
		this.checkfile = fileEntity.getCheckfile();		
		
	}
	
	public FileEntity dtoToEntity(FileDTO dto) {
		
		FileEntity fileEntity = FileEntity.builder()
							.email(dto.getEmail())
							.seqno(dto.getSeqno())
							.org_filename(dto.getOrg_filename())
							.stored_filename(dto.getStored_filename())
							.filesize(dto.getFilesize())
							.checkfile(dto.getCheckfile())
							.build();
		return fileEntity;
	}
	
}
