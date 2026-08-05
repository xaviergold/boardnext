package com.board.dto.board;

import lombok.*;

@Getter
@Setter
public class AddressDTO {

	private int seqno;
	private String zipcode;
	private String province;
	private String road;
	private String building;
	private String oldaddr;
	
}
