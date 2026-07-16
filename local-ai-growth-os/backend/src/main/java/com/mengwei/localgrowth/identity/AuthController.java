package com.mengwei.localgrowth.identity;

import jakarta.validation.Valid; import jakarta.validation.constraints.Email; import jakarta.validation.constraints.NotBlank;
import java.util.Map; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/auth") public class AuthController {
  private final AuthService service; public AuthController(AuthService service){this.service=service;}
  @PostMapping("/login") public Map<String,Object> login(@Valid @RequestBody LoginRequest request){return service.login(request.email(),request.password());}
  public record LoginRequest(@Email @NotBlank String email,@NotBlank String password){}
}

