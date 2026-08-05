package com.board.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "JPA_SECRETARY")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecretaryGoogleToken {

    @Id
    @Column(name = "EMAIL", length = 100)
    private String email;

    @Column(name = "ACCESS_TOKEN", length = 2000, nullable = false)
    private String accessToken;

    @Column(name = "REFRESH_TOKEN", length = 2000, nullable = true)
    private String refreshToken;

    @Column(name = "EXPIRES_AT", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "SCOPE", length = 500)
    private String scope;

    @Column(name = "CREATED_AT", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}