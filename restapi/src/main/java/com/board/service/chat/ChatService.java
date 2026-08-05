package com.board.service.chat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.board.dto.chat.ChatRoomDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class ChatService {

	private Map<String, ChatRoomDTO> chatRooms;
	private final ObjectMapper objectMapper;
	
	//스프링빈이 여러번 생성되지 않고 한번만 생성 --> chatRooms로 한번 생성되면 생성된 주소를 계속 유지
	@PostConstruct
	private void init() {
		chatRooms = new HashMap<>();
	}
	
	//생성된 전체 대화방 정보 가져 오기
	public List<ChatRoomDTO> findAllRooms(){
		return new ArrayList<>(chatRooms.values());
	}
	
	//대화방 아이디로 특정 대화방 정보 가져 오기 
	public ChatRoomDTO findRoomById(String roomId) {
		return chatRooms.get(roomId);
	}

	//대화방 새로 생성
	public ChatRoomDTO createRoom(String roomName) {
		String randomId = UUID.randomUUID().toString();
		ChatRoomDTO chatRoomDTO = ChatRoomDTO.builder()
								.roomId(randomId)
								.roomName(roomName)
								.build();
		chatRooms.put(randomId, chatRoomDTO);
		return chatRoomDTO;
	}
	
	public <T> void sendMessage(WebSocketSession session, T message) {
		try {
			session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
			//session.sendMessage(message);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
}
