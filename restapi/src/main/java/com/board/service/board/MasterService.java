package com.board.service.board;

import java.util.List;

import com.board.entity.FileEntity;

public interface MasterService {

	//삭제 파일 목록 갯수
	public Long filedeleteCount();
	
	//삭제 파일 목록 정보
	public List<FileEntity> filedeleteList();
	
	//파일 삭제
	public void deleteFile(Long fileseqno);	
	
}
