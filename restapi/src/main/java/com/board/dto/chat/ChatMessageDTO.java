package com.board.dto.chat;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
//채팅시 전달되는 메세지에 포함되어 있는 내용 : 대화방번호, 메세지작성자, 메세지 본문 
public class ChatMessageDTO {
	
	public enum MessageType {
		ENTER, TALK, CLOSE
	}
	
	private MessageType type; //메세지 타입
	private String roomId; //방번호
	private String sender; //메세지 작성자
	private String message; //전달 받은 메세지 본문

}
