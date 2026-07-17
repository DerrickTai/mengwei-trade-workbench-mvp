package com.mengwei.localgrowth.observation;

import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.shared.TenantAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
public class AiObservationController {
  private final AiObservationService service; private final TenantAccess access;
  public AiObservationController(AiObservationService service,TenantAccess access){this.service=service;this.access=access;}
  private Identity identity(String h){return access.identity(h);}
  @GetMapping("/consumer-questions") List<Map<String,Object>> questions(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@RequestParam Map<String,String> filters){return service.questions(identity(h),merchantId,filters);}
  @PostMapping("/consumer-questions") Map<String,Object> createQuestion(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@Valid @RequestBody QuestionInput in){return service.createQuestion(identity(h),merchantId,in);}
  @GetMapping("/consumer-questions/{questionId}") Map<String,Object> question(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@PathVariable UUID questionId){return service.question(identity(h),merchantId,questionId);}
  @PutMapping("/consumer-questions/{questionId}") Map<String,Object> updateQuestion(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@PathVariable UUID questionId,@Valid @RequestBody QuestionInput in){return service.updateQuestion(identity(h),merchantId,questionId,in);}
  @DeleteMapping("/consumer-questions/{questionId}") void deleteQuestion(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@PathVariable UUID questionId){service.deleteQuestion(identity(h),merchantId,questionId);}
  @GetMapping("/ai-observations") List<Map<String,Object>> observations(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@RequestParam Map<String,String> filters){return service.observations(identity(h),merchantId,filters);}
  @PostMapping("/ai-observations") Map<String,Object> createObservation(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@Valid @RequestBody ObservationInput in){return service.createObservation(identity(h),merchantId,in);}
  @GetMapping("/ai-observations/{observationId}") Map<String,Object> observation(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@PathVariable UUID observationId){return service.observation(identity(h),merchantId,observationId);}
  @PutMapping("/ai-observations/{observationId}") Map<String,Object> updateObservation(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@PathVariable UUID observationId,@Valid @RequestBody ObservationInput in){return service.updateObservation(identity(h),merchantId,observationId,in);}
  @DeleteMapping("/ai-observations/{observationId}") void deleteObservation(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@PathVariable UUID observationId){service.deleteObservation(identity(h),merchantId,observationId);}
  @GetMapping("/ai-observations/{observationId}/fact-issues") List<Map<String,Object>> issues(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@PathVariable UUID observationId){return service.issues(identity(h),merchantId,observationId);}
  @PostMapping("/ai-observations/{observationId}/fact-issues") Map<String,Object> createIssue(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@PathVariable UUID observationId,@Valid @RequestBody FactIssueInput in){return service.createIssue(identity(h),merchantId,observationId,in);}
  @PutMapping("/ai-observations/{observationId}/fact-issues/{issueId}") Map<String,Object> updateIssue(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@PathVariable UUID observationId,@PathVariable UUID issueId,@Valid @RequestBody FactIssueUpdate in){return service.updateIssue(identity(h),merchantId,observationId,issueId,in);}
  @DeleteMapping("/ai-observations/{observationId}/fact-issues/{issueId}") void deleteIssue(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@PathVariable UUID observationId,@PathVariable UUID issueId){service.deleteIssue(identity(h),merchantId,observationId,issueId);}
  @GetMapping("/ai-observations/dashboard") Map<String,Object> dashboard(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@RequestParam Map<String,String> filters){return service.dashboard(identity(h),merchantId,filters);}
  @PostMapping("/ai-observations/{observationId}/create-task") Map<String,Object> createTask(@RequestHeader("Authorization") String h,@PathVariable UUID merchantId,@PathVariable UUID observationId,@RequestBody TaskRequest in){return service.createTask(identity(h),merchantId,observationId,in.reasonCode());}

  public record QuestionInput(UUID storefrontId,@NotBlank String questionText,String city,String district,String industry,String consumerScenario,String targetAudience,String budgetText,@NotBlank String decisionStage,@NotNull Integer commercialValue,List<String> targetPlatforms,Boolean enabled,Boolean demo,String notes){}
  public record ObservationInput(UUID storefrontId,@NotNull UUID questionId,@NotBlank String aiPlatform,@NotBlank String observationMode,@NotNull OffsetDateTime observedAt,String locationText,@NotBlank String rawAnswer,UUID screenshotAssetId,Boolean merchantMentioned,Boolean merchantRecommended,Integer recommendationRank,@NotBlank String factCheckStatus,List<Map<String,Object>> citedSources,List<Map<String,Object>> mentionedCompetitors,@NotBlank String verificationStatus,Boolean demo,String notes){}
  public record FactIssueInput(UUID factId,@NotBlank String issueType,@NotBlank String observedValue,@NotBlank String severity,Boolean resolved,String resolutionNotes){}
  public record FactIssueUpdate(@NotNull Boolean resolved,String resolutionNotes){}
  public record TaskRequest(@NotBlank String reasonCode){}
}
