package com.mengwei.localgrowth.observationautomation;

import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.shared.TenantAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/source-evidence")
public class SourceEvidenceController {
  private final SourceEvidenceService service;
  private final TenantAccess access;

  public SourceEvidenceController(SourceEvidenceService service, TenantAccess access) {
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

  @PostMapping("/refresh")
  Map<String, Object> refresh(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId,
      @Valid @RequestBody RefreshInput input) {
    return service.refresh(
        identity(authorization), merchantId,
        input.url(), input.ownershipType(), input.sourceType());
  }

  @PutMapping("/{evidenceId}/review")
  Map<String, Object> review(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId,
      @PathVariable UUID evidenceId,
      @Valid @RequestBody ReviewInput input) {
    return service.review(
        identity(authorization), merchantId, evidenceId,
        input.manualReviewStatus(), input.evidenceGrade(),
        input.factConsistencyStatus(), input.notes());
  }

  public record RefreshInput(
      @NotBlank String url,
      String ownershipType,
      String sourceType) {}

  public record ReviewInput(
      @NotBlank String manualReviewStatus,
      @NotBlank String evidenceGrade,
      @NotBlank String factConsistencyStatus,
      String notes) {}
}
