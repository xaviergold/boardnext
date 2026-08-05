package com.board.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.board.dto.chat.ChatRoomDTO;
import com.board.service.chat.ChatService;

import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;

@RestController
@AllArgsConstructor
@Log4j2
public class RESTChatController {

	/*
	 1. 웹 소켓은 HTML5를 지원하는 웹브라우저에서 작동 
	 2. 실시간 웹 애플리케이션을 위해 설계된 통신 프로토콜이며, TCP(Transmission Control Protocol)를 기반으로 함
	 3. HTTP와 다르게 클라이언트와 서버 간에 최초 연결이 이루어지면, 이 연결을 통해 양방향 통신(Full Duplex)을 지속적으로 유지할수 있음	  
	 */
	
private final ChatService chatService;	
	
	@GetMapping("/api/chat/rooms")
	public ResponseEntity<List<ChatRoomDTO>> getAllRooms() {
		List<ChatRoomDTO> rooms = chatService.findAllRooms();
		return ResponseEntity.ok(rooms);
	}
	
	@PostMapping("/api/chat/makeRoom")
	public ResponseEntity<String> makeRoomId(@RequestParam("roomName") String roomName) {
		ChatRoomDTO chatRoomDTO = chatService.createRoom(roomName);
		String jsonString = String.format("{\"message\":\"good\",\"roomId\":\"%s\"}", chatRoomDTO.getRoomId());
		return ResponseEntity.ok(jsonString);
	}
	
}
