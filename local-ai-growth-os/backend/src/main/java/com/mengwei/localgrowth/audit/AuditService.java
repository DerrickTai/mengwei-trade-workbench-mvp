package com.mengwei.localgrowth.audit;
import java.time.OffsetDateTime; import java.util.UUID; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.stereotype.Service;
@Service public class AuditService { private final JdbcTemplate jdbc; public AuditService(JdbcTemplate jdbc){this.jdbc=jdbc;} public void log(UUID tenant,UUID actor,String action,String type,UUID id,String summary){jdbc.update("insert into audit_logs values (?,?,?,?,?,?,?,?)",UUID.randomUUID(),tenant,actor,action,type,id,summary,OffsetDateTime.now());} }

