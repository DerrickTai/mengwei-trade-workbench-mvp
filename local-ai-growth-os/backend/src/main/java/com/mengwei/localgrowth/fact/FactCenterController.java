package com.mengwei.localgrowth.fact;

import com.mengwei.localgrowth.fact.FactCenterService.*;
import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.shared.TenantAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/merchants/{merchantId}")
public class FactCenterController {
  private final FactCenterService service; private final TenantAccess access; public FactCenterController(FactCenterService service,TenantAccess access){this.service=service;this.access=access;} private Identity i(String h){return access.identity(h);}
  @GetMapping("/fact-center/summary") public Map<String,Object> summary(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId){return service.summary(i(h),merchantId);}
  @GetMapping("/fact-center/brands") public List<Map<String,Object>> brands(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId){return service.brands(i(h),merchantId);}
  @GetMapping("/storefronts") public List<Map<String,Object>> storefronts(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId){return service.storefronts(i(h),merchantId);}
  @PostMapping("/storefronts") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createStore(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId,@Valid @RequestBody StorefrontRequest r){return service.createStorefront(i(h),merchantId,new StorefrontInput(r.brandId,r.name,r.primary));}
  @PutMapping("/storefronts/{storefrontId}") public Map<String,Object> updateStore(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId,@PathVariable UUID storefrontId,@Valid @RequestBody StorefrontUpdateRequest r){return service.updateStorefront(i(h),merchantId,storefrontId,new StorefrontUpdate(r.name,r.primary,r.expectedVersion));}
  @GetMapping("/facts") public List<Map<String,Object>> facts(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId,@RequestParam(required=false)UUID storefrontId){return service.facts(i(h),merchantId,storefrontId);}
  @GetMapping("/facts/{factId}") public Map<String,Object> fact(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId,@PathVariable UUID factId){return service.fact(i(h),merchantId,factId);}
  @GetMapping("/facts/{factId}/versions") public List<Map<String,Object>> versions(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId,@PathVariable UUID factId){return service.versions(i(h),merchantId,factId);}
  @PostMapping("/facts") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createFact(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId,@Valid @RequestBody FactRequest r){return service.createFact(i(h),merchantId,r.toInput());}
  @PostMapping("/facts/{factId}/versions") public Map<String,Object> version(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId,@PathVariable UUID factId,@Valid @RequestBody VersionRequest r){return service.createVersion(i(h),merchantId,factId,r.toInput(),"FACT_VERSION_CREATED");}
  @PostMapping("/facts/{factId}/verify") public Map<String,Object> verify(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId,@PathVariable UUID factId,@Valid @RequestBody StatusRequest r){return service.verify(i(h),merchantId,factId,r.toInput());}
  @PostMapping("/facts/{factId}/expire") public Map<String,Object> expire(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId,@PathVariable UUID factId,@Valid @RequestBody StatusRequest r){return service.expire(i(h),merchantId,factId,r.toInput());}
  @PostMapping("/facts/{factId}/dispute") public Map<String,Object> dispute(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId,@PathVariable UUID factId,@Valid @RequestBody StatusRequest r){return service.dispute(i(h),merchantId,factId,r.toInput());}
  @PostMapping("/facts/{factId}/evidence-links") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> link(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId,@PathVariable UUID factId,@Valid @RequestBody EvidenceLinkRequest r){return service.linkEvidence(i(h),merchantId,factId,new EvidenceLinkInput(r.evidenceObservationId,r.assetId,r.externalUrl,r.evidenceType,r.notes));}
  @DeleteMapping("/facts/{factId}/evidence-links/{linkId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void unlink(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId,@PathVariable UUID factId,@PathVariable UUID linkId){service.unlinkEvidence(i(h),merchantId,factId,linkId);}
  public record StorefrontRequest(UUID brandId,@NotBlank String name,boolean primary){} public record StorefrontUpdateRequest(@NotBlank String name,boolean primary,@PositiveOrZero long expectedVersion){}
  public record FactRequest(UUID brandId,UUID storefrontId,@NotBlank String factScope,@NotBlank String factType,String factKey,@NotNull Object valueJson,@NotBlank String normalizedText,@NotBlank String status,OffsetDateTime effectiveFrom,OffsetDateTime effectiveTo,String verificationNotes,String sourceSummary){FactInput toInput(){return new FactInput(brandId,storefrontId,factScope,factType,factKey,valueJson,normalizedText,status,effectiveFrom,effectiveTo,verificationNotes,sourceSummary);}}
  public record VersionRequest(@NotNull Object valueJson,@NotBlank String normalizedText,@NotBlank String status,OffsetDateTime effectiveFrom,OffsetDateTime effectiveTo,String verificationNotes,String sourceSummary,@PositiveOrZero long expectedVersion){VersionInput toInput(){return new VersionInput(valueJson,normalizedText,status,effectiveFrom,effectiveTo,verificationNotes,sourceSummary,expectedVersion);}}
  public record StatusRequest(@PositiveOrZero long expectedVersion,OffsetDateTime effectiveFrom,OffsetDateTime effectiveTo,String notes){StatusInput toInput(){return new StatusInput(expectedVersion,effectiveFrom,effectiveTo,notes);}}
  public record EvidenceLinkRequest(UUID evidenceObservationId,UUID assetId,@Size(max=1200)String externalUrl,@NotBlank String evidenceType,String notes){}
}
