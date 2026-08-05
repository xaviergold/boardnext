package com.board.dto.chatbot;

import lombok.Builder;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
public class GoogleTokenDto implements Serializable {
 
	private static final long serialVersionUID = 1L;
	private String email;
    private String accessToken;
    private String refreshToken;
    private LocalDateTime expiresAt;
    private String scope;
}