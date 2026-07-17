package com.mengwei.localgrowth.observationautomation;

import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.shared.TenantAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/tracked-publications")
public class PublicationTrackingController {
  private final PublicationTrackingService service;
  private final TenantAccess access;

  public PublicationTrackingController(
      PublicationTrackingService service,
      TenantAccess access) {
    this.service = service;
    this.access = access;
  }

  private Identity identity(String authorization) {
    return access.identity(authorization);
  }

  @GetMapping
  List<Map<String, Object>> list(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId) {
    return service.list(identity(authorization), merchantId);
  }

  @PostMapping
  Map<String, Object> create(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId,
      @Valid @RequestBody PublicationInput input) {
    return service.create(identity(authorization), merchantId, input);
  }

  @PostMapping("/scan-citations")
  Map<String, Object> scan(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId) {
    return service.scan(identity(authorization), merchantId);
  }

  @GetMapping("/citation-events")
  List<Map<String, Object>> events(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId) {
    return service.events(identity(authorization), merchantId);
  }

  public record PublicationInput(
      UUID optimizationTaskId,
      @NotBlank String platform,
      @NotBlank String title,
      @NotBlank String url,
      OffsetDateTime publishedAt,
      String contentSha256,
      String status,
      Map<String, Object> metadata) {}
}
