package com.mengwei.localgrowth.evidence;

import com.mengwei.localgrowth.identity.AuthService.Identity; import com.mengwei.localgrowth.shared.TenantAccess;
import jakarta.validation.Valid; import jakarta.validation.constraints.NotBlank; import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime; import java.util.*; import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1") public class EvidenceController {
  private final EvidenceService service;private final TenantAccess access;public EvidenceController(EvidenceService service,TenantAccess access){this.service=service;this.access=access;}private Identity i(String h){return access.identity(h);}
  @GetMapping("/merchants/{merchantId}/evidence-observations") public List<Map<String,Object>> observations(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId){return service.observations(i(h),merchantId);}
  @PostMapping("/evidence-observations") public Map<String,Object> importEvidence(@RequestHeader("Authorization")String h,@Valid @RequestBody ObservationRequest r){return service.importObservation(i(h),new EvidenceService.ObservationInput(r.merchantId(),r.questionId(),r.platform(),r.modelName(),r.sourceType(),r.searchEnabled(),r.rawAnswer(),r.capturedAt(),r.citationLinks(),r.screenshotAssetId(),r.operatorNotes()));}
  @PostMapping("/formal-diagnostic-runs") public Map<String,Object> formalReport(@RequestHeader("Authorization")String h,@Valid @RequestBody FormalRunRequest r){return service.formalReport(i(h),r.merchantId());}
  public record ObservationRequest(@NotNull UUID merchantId,@NotNull UUID questionId,@NotBlank String platform,@NotBlank String modelName,@NotBlank String sourceType,boolean searchEnabled,@NotBlank String rawAnswer,@NotNull OffsetDateTime capturedAt,String citationLinks,UUID screenshotAssetId,String operatorNotes){} public record FormalRunRequest(@NotNull UUID merchantId){}
}
