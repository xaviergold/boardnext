package com.board.dto.board;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.board.entity.BoardEntity;
import com.board.entity.MemberEntity;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder

/*
1. serializable의 용도
- Serializable은 자바에서 Serialization(직렬화)을 가능하게 하겠다는 것을 선언하는 마커 인터페이스(Marker Interface).
- 구현해야 하는 메서드는 따로 없지만, 이 인터페이스를 붙여둠으로써 
  "이 클래스로 만든 객체는 바이트 상태로 변환해서 주고받을 수 있는 안전한 객체다"라고 
  자바 가상 머신(JVM)에 표시해 두는 용도

2. 직렬화(Serialization)와 역직렬화(Deserialization)란?
- 직렬화 (Serialization): 자바 메모리에 있는 객체(Object)를 파일에 저장하거나 네트워크를 통해 전송할 수 있도록 
  0과 1로 이루어진 연속적인 데이터(바이트 스트림)로 변환하는 과정.
- 역직렬화 (Deserialization): 반대로 저장된 파일이나 네트워크로 전송받은 바이트 스트림을 
  다시 원래의 자바 객체 형태로 복원하는 과정.
  
3. BoardDTO를 직렬화 해야 하는 이유
- BoardDTO에 저장된 내용이 Redis(외부 메모리 데이터베이스)에 저장하도록 설정 --> 캐쉬 구현
- 그런데, Redis는 외부 서버 시스템이므로 자바 메모리에 있는 객체 주소(참조값)를 이해할 수 없음. 
- 따라서, BoardDTO 객체에 저장된 데이터를 1차원 문자 배열로 변환 시켜 네트워크를 통해 Redis 서버로 전송해야 함.
- 자바는 Serializable이 구현된 객체만 바이트로 직렬화하여 Redis에 저장을 허용한. 
  만약 implements Serializable을 안하면 BoardDTO 객체를 Redis에 객체를 저장하는 순간 
  NotSerializableException 에러가 발생하며 서버가 멈추게 됨. 
  
4. Serializable을 구현할 때는 클래스 내부에 고유한 버전 아이디(serialVersionUID)를 명시해 주어야 함.  
- 클래스에 필드 하나를 추가하거나 수정하면 JVM은 클래스의 구조가 바뀌었다고 판단하여 기존에 저장해 둔 
  바이트 데이터를 읽어올 때(역직렬화) 에러를 낼 수 있음. 
- 따라서, 버전 ID를 1L 등으로 고정해 두면, 클래스가 약간 수정되어도 기존 데이터를 안전하게 매핑하여 읽어올 수 있음.

*/
public class BoardDTO implements Serializable{
	
	private static final long serialVersionUID = 1L;
	private Long seqno;
	private String email;
    private String writer;
    private String title;
    private LocalDateTime regdate;
    private String content;
    private int hitno;
    private int likecnt;
	private int dislikecnt;
	
	//생성자를 이용해서 Entity를 DTO로 이동
	public BoardDTO(BoardEntity boardEntity) {
		
		this.email = boardEntity.getEmail().getEmail();
		this.seqno = boardEntity.getSeqno();
		this.writer = boardEntity.getWriter();
		this.title = boardEntity.getTitle();
		this.regdate = boardEntity.getRegdate();
		this.content = boardEntity.getContent();
		this.hitno = boardEntity.getHitno();
		this.likecnt = boardEntity.getLikecnt();
		this.dislikecnt = boardEntity.getDislikecnt();
		
	}
	
	//DTO --> Entity로 이동
	public BoardEntity dtoToEntity(BoardDTO dto, MemberEntity memberEntity) {
		
		BoardEntity boardEntity = BoardEntity.builder()
								.email(memberEntity)
								.seqno(dto.getSeqno())
								.writer(dto.getWriter())
								.title(dto.getTitle())
								.regdate(dto.getRegdate())
								.content(dto.getContent())
								.hitno(dto.getHitno())
								.likecnt(dto.getLikecnt())
								.dislikecnt(dto.getDislikecnt())
								.build();
		return boardEntity;
		
	}
	
}
