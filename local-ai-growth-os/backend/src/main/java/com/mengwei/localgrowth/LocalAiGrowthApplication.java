package com.mengwei.localgrowth;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.mengwei.localgrowth.observationautomation.retest.RetestAutomationProperties;
import com.mengwei.localgrowth.publishing.PublisherWorkerProperties;

@SpringBootApplication
@EnableConfigurationProperties({RetestAutomationProperties.class, PublisherWorkerProperties.class})
public class LocalAiGrowthApplication {
  public static void main(String[] args) { SpringApplication.run(LocalAiGrowthApplication.class, args); }
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
  @Bean CommandLineRunner demoBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder) {
    return args -> {
      if (jdbc.queryForObject("select count(*) from tenants", Long.class) == 0) {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(); OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("insert into tenants values (?,?,?,?,?)", tenant, "演示商家团队", now, now, 0L);
        jdbc.update("insert into users values (?,?,?,?,?,?,?,?,?)", user, tenant, "demo@localgrowth.test", "演示管理员", encoder.encode("Demo123!"), "TENANT_ADMIN", now, now, 0L);
      }
    };
  }
}
