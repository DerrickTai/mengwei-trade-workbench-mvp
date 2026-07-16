package com.mengwei.localgrowth.shared;
import com.mengwei.localgrowth.identity.AuthService; import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException; import org.springframework.http.HttpStatus; import org.springframework.stereotype.Component;
@Component public class TenantAccess { private final AuthService auth; public TenantAccess(AuthService auth){this.auth=auth;} public AuthService.Identity identity(String authorization){ if(authorization==null||!authorization.startsWith("Bearer ")) throw new ApiException(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","请先登录");return auth.verify(authorization.substring(7)); } }

