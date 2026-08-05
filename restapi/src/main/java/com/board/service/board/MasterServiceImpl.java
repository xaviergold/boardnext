package com.board.service.board;

import java.util.List;

import org.springframework.stereotype.Service;
import com.board.entity.FileEntity;
import com.board.entity.repository.FileRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MasterServiceImpl implements MasterService{

	private final FileRepository fileRepository;
	
	//삭제 파일 목록 갯수
	@Override
	public Long filedeleteCount() {
		return fileRepository.countByCheckfile("N");
	}
	
	//삭제 파일 목록 정보
	@Override
	public List<FileEntity> filedeleteList(){
		return fileRepository.findByCheckfile("N");
	}
	
	//파일 정보 삭제
	@Override
	public void deleteFile(Long fileseqno) {
		FileEntity fileEntity = fileRepository.findById(fileseqno).get();		
		fileRepository.delete(fileEntity);
	}
	
}
