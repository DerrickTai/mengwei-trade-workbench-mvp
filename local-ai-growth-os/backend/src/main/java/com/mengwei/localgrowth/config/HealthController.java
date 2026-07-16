package com.mengwei.localgrowth.config;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lightweight Actuator-compatible health endpoint; avoids a separate monitoring dependency in this first image. */
@RestController
public class HealthController {
  private final JdbcTemplate jdbc;
  public HealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
  @GetMapping("/actuator/health")
  public Map<String, String> health() {
    jdbc.queryForObject("select 1", Integer.class);
    return Map.of("status", "UP");
  }
}
