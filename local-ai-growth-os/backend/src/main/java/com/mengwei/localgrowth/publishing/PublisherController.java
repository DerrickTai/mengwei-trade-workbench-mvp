package com.mengwei.localgrowth.publishing;

import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.shared.TenantAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
public class PublisherController {
  private final PublisherService service;
  private final TenantAccess access;

  public PublisherController(PublisherService service, TenantAccess access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping("/publisher-accounts")
  public List<Map<String, Object>> accounts(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId) {
    return service.accounts(identity(authorization), merchantId);
  }

  @PostMapping("/publisher-accounts")
  public Map<String, Object> createAccount(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId, @Valid @RequestBody AccountRequest request) {
    return service.createAccount(identity(authorization), merchantId, request.toInput());
  }

  @DeleteMapping("/publisher-accounts/{accountId}")
  public Map<String, Object> deactivateAccount(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId, @PathVariable UUID accountId) {
    return service.deactivateAccount(identity(authorization), merchantId, accountId);
  }

  @PostMapping("/publisher-jobs")
  public Map<String, Object> createJob(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId, @Valid @RequestBody JobRequest request) {
    return service.createJob(identity(authorization), merchantId, request.toInput());
  }

  @GetMapping("/publisher-jobs")
  public List<Map<String, Object>> jobs(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId) {
    return service.jobs(identity(authorization), merchantId);
  }

  @GetMapping("/publisher-jobs/{jobId}")
  public Map<String, Object> job(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId, @PathVariable UUID jobId) {
    return service.job(identity(authorization), merchantId, jobId);
  }

  private Identity identity(String authorization) { return access.identity(authorization); }

  public record AccountRequest(@NotBlank String displayName, String externalAccountId,
      @NotBlank String profileRef, String credentialRef, String defaultPublishMode,
      Map<String, Object> nonSensitiveConfig) {
    PublisherService.AccountInput toInput() {
      return new PublisherService.AccountInput(displayName, externalAccountId, profileRef,
          credentialRef, defaultPublishMode, nonSensitiveConfig);
    }
  }

  public record JobRequest(@NotNull UUID sourceDraftId, @NotNull UUID accountId,
      @NotBlank String publishMode, List<UUID> assetIds, @NotBlank String idempotencyKey) {
    PublisherService.JobInput toInput() {
      return new PublisherService.JobInput(sourceDraftId, accountId, publishMode,
          assetIds == null ? List.of() : assetIds, idempotencyKey);
    }
  }
}
