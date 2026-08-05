package com.board.dto.seat;

public record HoldResponse(
		String seatId,
		long expiresAtEpochMillis
) {
}
