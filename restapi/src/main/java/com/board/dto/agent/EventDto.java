package com.board.dto.agent;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EventDto {
    private String id;
    private String title;
    private String start;
    private String end;
    private String location;
    private String htmlLink;
}