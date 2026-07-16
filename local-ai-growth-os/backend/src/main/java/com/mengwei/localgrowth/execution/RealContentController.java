package com.mengwei.localgrowth.execution;
import com.mengwei.localgrowth.identity.AuthService.Identity;import com.mengwei.localgrowth.shared.TenantAccess;import jakarta.validation.Valid;import jakarta.validation.constraints.NotBlank;import java.util.*;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1") public class RealContentController {private final RealContentService service;private final TenantAccess access;public RealContentController(RealContentService s,TenantAccess a){service=s;access=a;}private Identity i(String h){return access.identity(h);}
@GetMapping("/ai/provider-status")Map<String,Object> status(){return service.status();}
@PostMapping("/ai/provider-test")Map<String,Object> test(){return service.testConnection();}
@PostMapping("/optimization-tasks/{id}/real-content")Map<String,Object> generate(@RequestHeader("Authorization")String h,@PathVariable UUID id,@Valid @RequestBody Request r){return service.generate(i(h),id,r.channel());}
public record Request(@NotBlank String channel){}
}
