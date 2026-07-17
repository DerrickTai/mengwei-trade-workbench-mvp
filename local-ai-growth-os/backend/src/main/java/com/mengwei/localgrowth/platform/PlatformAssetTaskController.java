package com.mengwei.localgrowth.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengwei.localgrowth.identity.AuthService;
import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/platform-asset-gaps")
public class PlatformAssetTaskController {
  private final JdbcTemplate db; private final NamedParameterJdbcTemplate named; private final AuthService auth; private final ObjectMapper json;
  public PlatformAssetTaskController(JdbcTemplate db,NamedParameterJdbcTemplate named,AuthService auth,ObjectMapper json){this.db=db;this.named=named;this.auth=auth;this.json=json;}
  @PostMapping("/{gapId}/create-task") public Map<String,Object> create(@RequestHeader("Authorization")String h,@PathVariable UUID merchantId,@PathVariable UUID gapId){var i=auth.verify(h.replaceFirst("Bearer ",""));var rows=db.queryForList("select g.*,p.platform from platform_asset_gaps g join platform_profiles p on p.id=g.platform_profile_id and p.tenant_id=g.tenant_id where g.id=? and g.tenant_id=? and g.merchant_id=?",gapId,i.tenantId(),merchantId);if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Gap不存在或无权访问");var g=rows.getFirst();if(g.get("generated_task_id")!=null){var t=db.queryForList("select * from optimization_tasks where id=? and tenant_id=?",g.get("generated_task_id"),i.tenantId());if(!t.isEmpty())return t.getFirst();}var runs=db.queryForList("select id from diagnostic_runs where tenant_id=? and merchant_id=? order by created_at desc limit 1",i.tenantId(),merchantId);if(runs.isEmpty())throw new ApiException(HttpStatus.BAD_REQUEST,"DIAGNOSTIC_RUN_REQUIRED","请先生成正式诊断后再创建整改任务");UUID diagnosticRunId=(UUID)runs.getFirst().get("id");String type=switch(String.valueOf(g.get("gap_type"))){case "MISSING_FIELD"->"LOCAL_LISTING";default->"ENTITY_PROFILE";};UUID id=UUID.randomUUID();OffsetDateTime now=OffsetDateTime.now();String description="平台："+g.get("platform")+"；字段："+g.get("field_key")+"；当前平台值："+value(g.get("platform_value_snapshot"))+"；事实目标值："+value(g.get("fact_value_snapshot"))+"；差异："+g.get("difference_summary")+"。整改要求：人工核验并更新平台资料；gapId="+gapId+"；platformProfileId="+g.get("platform_profile_id");MapSqlParameterSource p=new MapSqlParameterSource().addValue("id",id).addValue("tenantId",i.tenantId()).addValue("merchantId",merchantId).addValue("diagnosticRunId",diagnosticRunId).addValue("taskType",type).addValue("title","整改 "+g.get("platform")+" · "+g.get("field_key")).addValue("description",description).addValue("priority",g.get("priority")).addValue("targetQuestionIds","[]").addValue("recommendedChannels",channels(String.valueOf(g.get("platform")))).addValue("now",now).addValue("userId",i.userId());named.update("insert into optimization_tasks(id,tenant_id,merchant_id,diagnostic_run_id,task_type,title,description,priority,target_question_ids,recommended_channels,status,created_at,updated_at,created_by,updated_by,version) values(:id,:tenantId,:merchantId,:diagnosticRunId,:taskType,:title,:description,:priority,cast(:targetQuestionIds as jsonb),cast(:recommendedChannels as jsonb),'TODO',:now,:now,:userId,:userId,0)",p);db.update("update platform_asset_gaps set generated_task_id=?,updated_at=?,updated_by=? where id=? and tenant_id=?",id,now,i.userId(),gapId,i.tenantId());return db.queryForMap("select * from optimization_tasks where id=?",id);}
  private String value(Object v){return v==null?"无":String.valueOf(v);}private String channels(String p){try{return json.writeValueAsString(List.of(p));}catch(Exception e){throw new IllegalStateException(e);}}
}
