package com.mengwei.localgrowth.identity;

import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service public class AuthService {
  private final JdbcTemplate jdbc; private final PasswordEncoder encoder; private final String secret;
  public AuthService(JdbcTemplate jdbc, PasswordEncoder encoder, @Value("${app.auth-token}") String secret){this.jdbc=jdbc;this.encoder=encoder;this.secret=secret;}
  public Map<String,Object> login(String email,String password) {
    var rows=jdbc.queryForList("select id,tenant_id,display_name,password_hash,role from users where email=?",email);
    if(rows.isEmpty() || !encoder.matches(password,(String)rows.getFirst().get("password_hash"))) throw new ApiException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","邮箱或密码不正确");
    var u=rows.getFirst(); String body=u.get("tenant_id")+":"+u.get("id"); return Map.of("accessToken", body+":"+sign(body),"user",Map.of("id",u.get("id"),"name",u.get("display_name"),"role",u.get("role")));
  }
  public Identity verify(String token) { try { String[] p=token.split(":"); if(p.length!=3 || !MessageDigest.isEqual(sign(p[0]+":"+p[1]).getBytes(),p[2].getBytes())) throw new IllegalArgumentException(); return new Identity(UUID.fromString(p[0]),UUID.fromString(p[1])); } catch(Exception e){throw new ApiException(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","登录状态无效或已过期");} }
  private String sign(String text){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  public record Identity(UUID tenantId, UUID userId) {}
}

