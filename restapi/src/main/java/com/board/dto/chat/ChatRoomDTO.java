package com.board.dto.chat;

import java.util.HashSet;
import java.util.Set;

import org.springframework.web.socket.WebSocketSession;

import com.board.service.chat.ChatService;

import lombok.Builder;
import lombok.Getter;

@Getter
public class ChatRoomDTO {

	private String roomId;
	private String roomName;
	private Set<WebSocketSession> sessions = new HashSet<>();
	
	@Builder
	public ChatRoomDTO(String roomId, String roomName) {
		this.roomId = roomId;
		this.roomName = roomName;
	}

	public void handleActions(WebSocketSession session, ChatMessageDTO chatMessage, ChatService chatService) {
		if(chatMessage.getType().equals(ChatMessageDTO.MessageType.ENTER)) {
			sessions.add(session);
			chatMessage.setMessage(chatMessage.getSender() + "님이 입장했습니다.");
		}
		sendMessage(chatMessage, chatService);
	}
	
	public <T> void sendMessage(T message, ChatService chatService){
		sessions.parallelStream().forEach(session -> chatService.sendMessage(session, message));		
	}

}
