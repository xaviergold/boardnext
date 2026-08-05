package com.board.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity(name="board")
@Table(name="jpa_board")
public class BoardEntity {

	/*
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "BOARD_SEQ")
     : primary key의 증가(increment) 전략 설정
   --> SEQUENCE : Oracle, PostgreSQL 등과 같은 시퀀스가 있는 DBMS에 적용
   --> IDENTITY : 기본키 생성을 데이터베이스에 완전히 일임하는 방식으로 MySQL(AUTO_INCREMENT)에 적용
        예) MySQL --> @GeneratedValue(strategy = GenerationType.SEQUENCE)
   --> TABLE : @TableGenerator를 만들어서 사용
        예)  
         @Entity
         @TableGenerator(
                 name = "BOARD_SEQ_GENERATOR",
                 table = "tbl_board",
                 pkColumnName = "BOARD_NAME",
                 valueColumnName = "NEXTVAL",
                 pkColumnValue = "BOARD_SEQ",
                 initialVlaue = 0,
                 allocationSize = 1
          )
          public class Board {
              @Id
              @GeneratedValue(strategy = GenerationType.TABLE, generator = "BOARD_SEQ_GENERATOR")
              Long id;
          }
   --> AUTO --> JPA가 데이터베이스 방언(Dialect)에 맞춰 위 3가지 중 하나를 선택해서 사용 --> 조금 주의
        ※ 데이터베이스 방언(Dialect) : ORM에서 특정 데이터베이스에서 사용하는 고유의 SQL 문법
           --> MySQL: spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect
               Oracle: spring.jpa.database-platform=org.hibernate.dialect.OracleDialect
               PostgreSQL: spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
               H2 (테스트용 인메모리 DB): spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
           --> 최신 버전의 스프링부트에서는 spring.jpa.database=oracle 설정만으로도 JPA가 Dialect를 인식함.
        ** Oracle 또는 PostgreSQL과 연결되었을 때 JPA는 이 데이터베이스들이 시퀀스를 지원한다는 것을 알고 있어 
           내부적으로 GenerationType.SEQUENCE로 자동 전환하며, 데이터베이스에 HIBERNATE_SEQUENCE라는 
           기본 시퀀스 오브젝트를 만들어서 사용.
        ** MySQL 또는 MariaDB와 연결되었을 때는 JPA는 이 데이터베이스들이 시퀀스를 지원하지 않는다는 것을 알고 있으며, 
           Hibernate 5 버전 이후부터는 MySQL에서 AUTO를 사용하면 보통 GenerationType.TABLE 전략을 선택하여 
           키 관리용 테이블을 새로 만듬. (또는 버전에 따라 IDENTITY를 선택하기도 함.)	 
        ** MySQL에서 AUTO를 사용하면 개발자의 의도(AUTO_INCREMENT)와 다르게 TABLE 전략이 선택되는 경우가 많음.
           따라서, 실제 서비스를 구축할 때는 데이터베이스가 쉽게 바뀌지 않으므로, 
           데이터베이스 특성에 맞춰 명시적으로 지정하는 것이 안전함.
	 */
	
	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "BOARD_SEQ")
	@SequenceGenerator(name="BOARD_SEQ", sequenceName = "jpa_board_seq", initialValue = 1, allocationSize = 1)
	@Column(name="seqno", nullable=false)
	private Long seqno; //JPA의 primary 키를 숫자로 사용할때는 만드시 long 타입으로 해야하고 wrapper 클래스 사용. 

	@Column(name="writer", length=50, nullable=false)
	private String writer;
	
	@Column(name="title", length=200, nullable=false)
	private String title;
	
	@Column(name="content", length=2000, nullable=false)
	private String content;
	
	@Column(name="regdate", nullable=false)
	private LocalDateTime regdate;
	
	@Column(name="hitno", nullable=true)
	private int hitno;
	
	@Column(name="likecnt", nullable=true)
	private int likecnt;
	
	@Column(name="dislikecnt", nullable=true)
	private int dislikecnt;

	//FK 만들기
	//FK 읽어 올때 Eager, Lazy 두가지 타입이 있음
	//Eager는 부모키가 있는 테이블부터 검사해서 부모키가 제대로 되어 있는지 확인하고 자식키를 읽음.-> 정확도는 높지만 성능이 저하
	//Lazy는 자식키가 있는 테이블만 읽음. -> 정확도는 떨어지지만 성능이 향상.
	//alter table tbl_board add constraint fk_tbl_board_email foreign key(email) REFERENCES TBL_member(email) on delete cascade ;
	/*

	1. 프록시 객체의 핵심 개념: "필요할 때만 움직인다"
	- 가장 이해하기 쉬운 예시가 바로 JPA의 지연 로딩(Lazy Loading).
	- 예를 들어, 게시글(Board) 엔티티와 회원(Member) 엔티티가 연관되어 있다고 가정할 경우, 
	- 게시글 목록 화면에서는 제목과 작성일만 보여주면 되고, 회원 정보(이메일, 주소 등)는 굳이 가져올 필요가 없음.
	- 이때 JPA는 데이터베이스에서 게시글만 조회하고, 회원 자리에는 진짜 회원 객체 대신 프록시(가짜) 회원 객체를 집어넣어 둠.

	2. 내부 동작 원리 (데이터가 실제로 필요해질 때)
	- 프록시 객체는 처음에는 알맹이(데이터)가 없고 진짜 객체의 참조(주소값)만 가질 수 있는 빈 껍데기 상태임.
	- 호출: 프로그램에서 member.getName()처럼 가짜 객체의 메서드를 호출.
	- 조회: 프록시 객체는 그제야 "아, 진짜 데이터가 필요하구나!"라고 깨닫고, 
	       데이터베이스에 쿼리를 날려 진짜 회원 객체를 생성 ==> 이를 '프록시 초기화'라고 함
	- 위임: 프록시 객체가 가지고 있는 실제 객체 참조를 통해 진짜객체.getName()을 호출하여 결과를 반환.

	3. 프록시 객체를 사용하는 2가지 주요 이유
	- 연산과 리소스의 최적화 (JPA 지연 로딩)
	  데이터를 당장 쓰지 않을 때는 가짜 객체로 놔두었다가, 실제 사용할 때만 DB에서 가져오므로 
	  불필요한 데이터베이스 조회를 줄여 성능을 최적화할 수 있음.
	- 부가 기능의 추가 (스프링 AOP / @Transactional)
	- 스프링에서 @Transactional 애노테이션을 붙이면, 스프링은 우리가 만든 클래스를 상속받은 프록시 객체를 자동으로 만들어 냄.
	- 이 프록시 객체는 개발자가 만든 코드(진짜 객체)를 실행하기 전후에 트랜잭션 시작(begin), 트랜잭션 제출(commit) 같은 
	  공통적인 부가 기능을 대신 처리해 주어 개발자는 핵심 비즈니스 로직에만 집중...
	  
	4. application.yaml의 "fail-on-empty-beans: false" 설정이 바로 이 프록시 객체 때문에 필요함.
    - JPA가 만들어 둔 프록시 객체는 가짜 객체이기 때문에, 일반적인 자바 객체처럼 필드나 Getter 메서드가 정상적으로 
      조회되지 않는 형태(껍데기)인 경우가 많음.
    - 이 상태에서 Jackson 라이브러리가 "컨트롤러에서 리턴된 이 객체를 JSON으로 변환해야지!" 하고 접근하면, 
      알맹이가 비어 있는 프록시 객체를 보고 "이거 빈 객체인데 어떻게 직렬화하라는 거지?"라며 에러를 던지게 됨.
    - fail-on-empty-beans: false는 바로 이런 프록시 객체를 만나더라도 에러로 서버를 중단시키지 말고, 
      빈 JSON {}으로 변환해서 안전하게 넘어가도록 도와주는 역할을 함.  

	*/
	@ManyToOne(fetch = FetchType.LAZY)
	@OnDelete(action = OnDeleteAction.CASCADE)
	@JoinColumn(name="email", nullable = false)
	private MemberEntity email;

}
