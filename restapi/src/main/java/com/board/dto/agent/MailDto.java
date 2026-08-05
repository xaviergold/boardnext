package com.board.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailDto implements Serializable {

	private static final long serialVersionUID = 1L;
	private String id;
    private String from;
    private String subject;
    private String date;
    private String snippet;
    private String body;        
    private Long internalDate;
}