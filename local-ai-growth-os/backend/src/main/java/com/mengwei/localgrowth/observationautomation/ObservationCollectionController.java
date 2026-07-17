package com.mengwei.localgrowth.observationautomation;

import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.shared.TenantAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/observation-automation")
public class ObservationCollectionController {
  private final ObservationCollectionService service;
  private final TenantAccess access;

  public ObservationCollectionController(
      ObservationCollectionService service,
      TenantAccess access) {
    this.service = service;
    this.access = access;
  }

  private Identity identity(String authorization) {
    return access.identity(authorization);
  }

  @GetMapping("/collector-configs")
  List<Map<String, Object>> configs(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId) {
    return service.configs(identity(authorization), merchantId);
  }

  @PostMapping("/collector-configs")
  Map<String, Object> createConfig(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId,
      @Valid @RequestBody CollectorConfigInput input) {
    return service.createConfig(identity(authorization), merchantId, input);
  }

  @PutMapping("/collector-configs/{configId}")
  Map<String, Object> updateConfig(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId,
      @PathVariable UUID configId,
      @Valid @RequestBody CollectorConfigInput input) {
    return service.updateConfig(identity(authorization), merchantId, configId, input);
  }

  @PostMapping("/runs")
  Map<String, Object> run(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId,
      @Valid @RequestBody RunInput input) {
    return service.run(identity(authorization), merchantId, input);
  }

  @GetMapping("/runs")
  List<Map<String, Object>> runs(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId) {
    return service.runs(identity(authorization), merchantId);
  }

  @GetMapping("/runs/{runId}/results")
  List<Map<String, Object>> results(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId,
      @PathVariable UUID runId) {
    return service.results(identity(authorization), merchantId, runId);
  }

  @PostMapping("/results/{resultId}/promote")
  Map<String, Object> promote(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId,
      @PathVariable UUID resultId) {
    return service.promote(identity(authorization), merchantId, resultId);
  }

  @PostMapping("/results/{resultId}/reject")
  Map<String, Object> reject(
      @RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId,
      @PathVariable UUID resultId) {
    return service.reject(identity(authorization), merchantId, resultId);
  }

  public record CollectorConfigInput(
      @NotBlank String name,
      @NotBlank String aiPlatform,
      @NotNull CollectionChannel collectionChannel,
      @NotBlank String providerCode,
      @NotBlank String apiBaseUrl,
      @NotBlank String modelName,
      @NotBlank String secretEnvName,
      Boolean webSearchEnabled,
      String locationCountry,
      String locationText,
      Map<String, Object> requestOptions,
      Boolean autoCreateDraft,
      Boolean enabled,
      String scheduleCron) {}

  public record RunInput(
      @NotEmpty List<UUID> collectorConfigIds,
      @NotEmpty List<UUID> questionIds,
      Boolean autoCreateDraft) {}
}
