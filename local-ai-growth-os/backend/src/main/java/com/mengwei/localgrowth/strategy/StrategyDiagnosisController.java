package com.mengwei.localgrowth.strategy;

import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.shared.TenantAccess;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
public class StrategyDiagnosisController {
  private final StrategyDiagnosisService service; private final TenantAccess access;
  public StrategyDiagnosisController(StrategyDiagnosisService service,TenantAccess access){this.service=service;this.access=access;}
  private Identity identity(String h){return access.identity(h);}
  @GetMapping("/geo-strategy-config") Map<String,Object> config(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId){return service.config(identity(h),merchantId);}
  @PutMapping("/geo-strategy-config") Map<String,Object> save(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@Valid @RequestBody ConfigInput input){return service.saveConfig(identity(h),merchantId,input);}
  @GetMapping("/geo-strategy-dashboard") Map<String,Object> dashboard(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@RequestParam Map<String,String> filters){return service.dashboard(identity(h),merchantId,filters);}
  @PostMapping("/geo-diagnoses") Map<String,Object> diagnose(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@RequestBody(required=false) DiagnoseInput input){return service.diagnose(identity(h),merchantId,input==null?null:input.dateFrom(),input==null?null:input.dateTo());}
  @GetMapping("/geo-diagnoses") List<Map<String,Object>> snapshots(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId){return service.snapshots(identity(h),merchantId);}
  @GetMapping("/geo-diagnoses/{snapshotId}") Map<String,Object> snapshot(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@PathVariable UUID snapshotId){return service.snapshot(identity(h),merchantId,snapshotId);}
  @PostMapping("/geo-diagnoses/{snapshotId}/optimization-tasks") List<Map<String,Object>> tasks(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@PathVariable UUID snapshotId){return service.createTasks(identity(h),merchantId,snapshotId);}
  public record ConfigInput(List<String> brandTerms,List<String> ambiguousTerms,List<UUID> competitorIds,List<UUID> questionIds,List<String> targetPlatforms,List<String> ownedDomains,String notes){}
  public record DiagnoseInput(OffsetDateTime dateFrom,OffsetDateTime dateTo){}
}
