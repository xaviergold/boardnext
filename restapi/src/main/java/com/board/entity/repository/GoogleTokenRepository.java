package com.board.entity.repository;

import com.board.entity.SecretaryGoogleToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface GoogleTokenRepository extends JpaRepository<SecretaryGoogleToken, String> {

    Optional<SecretaryGoogleToken> findByEmail(String email);

    void deleteByEmail(String email);

    boolean existsByEmail(String email);
}