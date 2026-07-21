package com.mengwei.localgrowth.observationautomation.retest;

import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.shared.TenantAccess;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/retest-automation")
public class RetestAutomationController {
  private final RetestAutomationService service;
  private final TenantAccess access;

  public RetestAutomationController(RetestAutomationService service, TenantAccess access) {
    this.service = service;
    this.access = access;
  }

  private Identity identity(String authorization) { return access.identity(authorization); }

  @GetMapping("/experiments")
  public List<Map<String,Object>> list(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId) {
    return service.list(identity(authorization), merchantId);
  }

  @PostMapping("/experiments")
  public Map<String,Object> create(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId,
      @RequestBody RetestAutomationService.CreateInput input) {
    return service.create(identity(authorization), merchantId, input);
  }

  @GetMapping("/experiments/{experimentId}/report")
  public List<Map<String,Object>> report(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId, @PathVariable UUID experimentId) {
    return service.report(identity(authorization), merchantId, experimentId);
  }

  @PostMapping("/experiments/{experimentId}/activate")
  public ResponseEntity<Void> activate(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId, @PathVariable UUID experimentId) {
    service.activate(identity(authorization), merchantId, experimentId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/experiments/{experimentId}/pause")
  public ResponseEntity<Void> pause(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId, @PathVariable UUID experimentId) {
    service.pause(identity(authorization), merchantId, experimentId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/experiments/{experimentId}/schedule-points/{schedulePointId}/execute")
  public Map<String, Object> executeSchedulePoint(@RequestHeader("Authorization") String authorization,
      @PathVariable UUID merchantId, @PathVariable UUID experimentId, @PathVariable UUID schedulePointId) {
    return service.executeSchedulePoint(identity(authorization), merchantId, experimentId, schedulePointId);
  }
}
