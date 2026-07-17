package com.mengwei.localgrowth.observationautomation;

import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.shared.TenantAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/retest-experiments")
public class RetestExperimentController {
  private final RetestExperimentService service;
  private final TenantAccess access;

  public RetestExperimentController(
      RetestExperimentService service,
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
      @Valid @RequestBody ExperimentInput input) {
    return service.create(identity(authorization), merchantId, input);
  }

  @PostMapping("/{experimentId}/compare/{retestSnapshotId}")
  Map<String, Object> compare(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId,
      @PathVariable UUID experimentId,
      @PathVariable UUID retestSnapshotId,
      @RequestBody(required = false) CompareInput input) {
    return service.compare(
        identity(authorization),
        merchantId,
        experimentId,
        retestSnapshotId,
        input == null ? null : input.volatility());
  }

  public record ExperimentInput(
      @NotBlank String name,
      @NotNull UUID baselineSnapshotId,
      UUID interventionTaskId,
      List<UUID> questionIds,
      List<String> aiPlatforms,
      List<CollectionChannel> collectionChannels,
      String locationText,
      @Min(1) @Max(20) Integer repetitions,
      Map<String, Object> comparisonOptions) {}

  public record CompareInput(Double volatility) {}
}
