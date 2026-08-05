package com.board.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class PgVectorDataSourceConfig {

    // 1. Oracle 기본 JdbcTemplate (@Primary → BoardImageService, BoardQueryService 등이 이걸 주입받음)
    @Bean(name = "jdbcTemplate")
    @Primary
    public JdbcTemplate jdbcTemplate(
            @Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    // 2. PGVector 전용 DataSource
    @Bean(name = "pgVectorDataSource")
    @ConfigurationProperties(prefix = "pgvector.datasource")
    public DataSource pgVectorDataSource() {
        return new HikariDataSource();
    }

    // 3. PGVector 전용 JdbcTemplate
    @Bean(name = "pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(
            @Qualifier("pgVectorDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    // 4. VectorStore 수동 등록 → AutoConfiguration 차단
    @Bean
    @ConditionalOnMissingBean(PgVectorStore.class)
    public PgVectorStore vectorStore(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1536)
                .initializeSchema(true)
                .build();
    }
}