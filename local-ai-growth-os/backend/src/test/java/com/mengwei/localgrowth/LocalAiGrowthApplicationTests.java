package com.mengwei.localgrowth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class LocalAiGrowthApplicationTests {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("localgrowth_test")
      .withUsername("localgrowth_test")
      .withPassword("localgrowth_test");

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void registerPostgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("app.auth-token", () -> "test-auth-token");
    registry.add("app.storage.endpoint", () -> "http://localhost:9000");
    registry.add("app.storage.access-key", () -> "test-access");
    registry.add("app.storage.secret-key", () -> "test-secret");
    registry.add("app.storage.bucket", () -> "test-bucket");
  }

  @Test
  void contextLoads() {
    assertThat(jdbcTemplate.queryForObject(
        "select success from flyway_schema_history where version = '21'", Boolean.class))
        .isTrue();
  }
}
