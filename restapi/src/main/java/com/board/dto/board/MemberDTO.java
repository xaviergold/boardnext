package com.board.dto.board;

import java.time.LocalDateTime;

import com.board.entity.MemberEntity;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MemberDTO {
	
	private String email;
	private String username;
	private String password;
	private String gender;
	private String hobby;
	private String job;
	private String description;
	private String zipcode;
	private String address;
	private String telno;
	private String nickname;	
	private String role;
	private String org_filename;
	private String stored_filename;
	private Long filesize;
	private LocalDateTime regdate;
	private String FromSocial;
	private String secretary;
	private String authkey;
	private LocalDateTime lastlogindate;
	private LocalDateTime lastlogoutdate;
	private LocalDateTime lastpwdate;
	private LocalDateTime lastpwcheckdate;	
	
	public MemberDTO(MemberEntity memberEntity) {
		
		this.email = memberEntity.getEmail();
		this.username = memberEntity.getUsername();
		this.password = memberEntity.getPassword();
		this.gender = memberEntity.getGender();
		this.hobby = memberEntity.getHobby();
		this.job = memberEntity.getJob();
		this.description = memberEntity.getDescription();
		this.zipcode = memberEntity.getZipcode();
		this.address = memberEntity.getAddress();
		this.telno = memberEntity.getTelno();
		this.nickname = memberEntity.getNickname();
		this.role = memberEntity.getRole();
		this.org_filename = memberEntity.getOrg_filename();
		this.stored_filename = memberEntity.getStored_filename();
		this.filesize = memberEntity.getFilesize();
		this.regdate = memberEntity.getRegdate();
		this.lastlogindate = memberEntity.getLastlogindate();
		this.lastlogoutdate = memberEntity.getLastlogoutdate();
		this.lastpwdate = memberEntity.getLastpwdate();
		this.lastpwcheckdate = memberEntity.getLastpwcheckdate();
		this.FromSocial = memberEntity.getFromSocial();
		this.secretary = memberEntity.getSecretary();
		this.authkey = memberEntity.getAuthkey();
				
	}
	
	public MemberEntity dtoToEntity(MemberDTO memberDTO) {
		
		MemberEntity memberEntity = MemberEntity.builder()
											.email(memberDTO.getEmail())
											.username(memberDTO.getUsername())
											.password(memberDTO.getPassword())
											.gender(memberDTO.getGender())
											.hobby(memberDTO.getHobby())
											.job(memberDTO.getJob())
											.description(memberDTO.getDescription())
											.zipcode(memberDTO.getZipcode())
											.address(memberDTO.getAddress())
											.telno(memberDTO.getTelno())
											.nickname(memberDTO.getNickname())
											.role(memberDTO.getRole())
											.org_filename(memberDTO.getOrg_filename())
											.stored_filename(memberDTO.getStored_filename())
											.filesize(memberDTO.getFilesize())
											.regdate(memberDTO.getRegdate())
											.lastlogindate(memberDTO.getLastlogindate())
											.lastlogoutdate(memberDTO.getLastlogoutdate())
											.lastpwdate(memberDTO.getLastpwdate())
											.lastpwcheckdate(memberDTO.getLastpwcheckdate())
											.FromSocial(memberDTO.getFromSocial())
											.secretary(memberDTO.getSecretary())
											.authkey(memberDTO.getAuthkey())
											.build();
		return memberEntity;
	}
	
}
