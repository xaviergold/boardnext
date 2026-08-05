package com.board.entity.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.board.entity.AddressEntity;

	public interface AddressRepository extends JpaRepository<AddressEntity,Long>{
		Page<AddressEntity> findByRoadContainingOrBuildingContaining(String addrSearch1, String addrSearch2, Pageable pageable);
	}
