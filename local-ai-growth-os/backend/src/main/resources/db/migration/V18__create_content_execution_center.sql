-- M6: content execution center. V17 is already deployed and intentionally untouched.
create table geo_execution_plans (
  id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id),
  diagnosis_snapshot_id uuid not null references geo_diagnosis_snapshots(id), strategy_config_id uuid references geo_strategy_configs(id),
  idempotency_key varchar(160) not null, name varchar(200) not null, period_start date not null, period_end date not null,
  status varchar(24) not null default 'DRAFT', executive_summary text, methodology_notes text, limitation_notes text,
  created_at timestamptz not null, updated_at timestamptz not null, created_by uuid, updated_by uuid, version bigint not null default 0,
  constraint ck_m6_plan_period check(period_end >= period_start), constraint ck_m6_plan_status check(status in ('DRAFT','ACTIVE','COMPLETED','CANCELLED')),
  unique(tenant_id, merchant_id, idempotency_key)
);
create table geo_execution_work_items (
  id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id),
  execution_plan_id uuid not null references geo_execution_plans(id) on delete cascade, optimization_task_id uuid references optimization_tasks(id),
  task_type varchar(64) not null, title varchar(300) not null, description text, target_platform varchar(64), target_question_ids jsonb not null default '[]',
  business_value smallint not null default 3, gap_severity smallint not null default 3, execution_feasibility smallint not null default 3, evidence_confidence smallint not null default 3,
  priority_score numeric(10,4), priority_level varchar(16) not null default 'MEDIUM', due_date date, status varchar(32) not null default 'PLANNED', success_criteria jsonb not null default '{}',
  created_at timestamptz not null, updated_at timestamptz not null, created_by uuid, updated_by uuid, version bigint not null default 0,
  constraint ck_m6_work_dimensions check(business_value between 1 and 5 and gap_severity between 1 and 5 and execution_feasibility between 1 and 5 and evidence_confidence between 1 and 5),
  constraint ck_m6_work_status check(status in ('PLANNED','IN_PROGRESS','REVIEW_PENDING','APPROVED','COMPLETED','BLOCKED','CANCELLED','SUPERSEDED'))
);
create table geo_content_briefs (
  id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id), work_item_id uuid not null references geo_execution_work_items(id) on delete cascade,
  version integer not null default 1, status varchar(24) not null default 'BRIEF_DRAFT', content_goal text not null, target_audience text, target_platform varchar(64) not null, content_type varchar(64) not null,
  target_question_ids jsonb not null default '[]', required_fact_ids jsonb not null default '[]', evidence_pack jsonb not null default '[]', evidence_pack_hash varchar(64), prohibited_claims jsonb not null default '[]',
  content_structure jsonb not null default '[]', call_to_action text, success_criteria jsonb not null default '{}', retest_criteria jsonb not null default '{}', confirmed_at timestamptz, confirmed_by uuid,
  created_at timestamptz not null, updated_at timestamptz not null, created_by uuid, updated_by uuid, version_lock bigint not null default 0,
  unique(work_item_id,version), constraint ck_m6_brief_status check(status in ('BRIEF_DRAFT','BRIEF_CONFIRMED','SUPERSEDED'))
);
create table geo_content_generation_runs (
  id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id), brief_id uuid not null references geo_content_briefs(id) on delete cascade,
  idempotency_key varchar(160) not null, provider_code varchar(64) not null, model_name varchar(128), prompt_template_version varchar(64) not null, input_hash varchar(64) not null,
  status varchar(24) not null default 'PENDING', attempt_count integer not null default 0, error_code varchar(96), error_message text, usage_json jsonb, created_at timestamptz not null, updated_at timestamptz not null,
  constraint ck_m6_generation_status check(status in ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')), unique(tenant_id,idempotency_key)
);
create table geo_content_drafts (
  id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id), brief_id uuid not null references geo_content_briefs(id) on delete cascade, generation_run_id uuid references geo_content_generation_runs(id),
  platform varchar(64) not null, content_type varchar(64) not null, version integer not null default 1, status varchar(40) not null default 'DRAFT_GENERATED', title text, excerpt text, body text not null, keywords text, meta_description text,
  prompt_template_version varchar(64), input_evidence_hash varchar(64), content_hash varchar(64) not null, created_at timestamptz not null, updated_at timestamptz not null, created_by uuid, updated_by uuid,
  unique(brief_id,platform,version), constraint ck_m6_draft_status check(status in ('DRAFT_GENERATED','FACT_CHECK_FAILED','FACT_CHECK_PASSED','RISK_SCAN_FAILED','RISK_SCAN_PASSED','REVIEW_PENDING','APPROVED','REJECTED','READY_TO_PUBLISH','PUBLISHED','TRACKING','RETESTING','CLOSED','SUPERSEDED'))
);
create table geo_content_claims (
  id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id), draft_id uuid not null references geo_content_drafts(id) on delete cascade,
  claim_text text not null, support_status varchar(32) not null, severity varchar(16) not null default 'MEDIUM', rationale text, created_at timestamptz not null, created_by uuid,
  constraint ck_m6_claim_support check(support_status in ('VERIFIED_FACT','SUPPORTED_EVIDENCE','USER_CONFIRMED','UNSUPPORTED','CONFLICTED')), constraint ck_m6_claim_severity check(severity in ('LOW','MEDIUM','HIGH','BLOCKED'))
);
create table geo_content_risk_scans (
  id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id), draft_id uuid not null references geo_content_drafts(id) on delete cascade,
  trigger_type varchar(48) not null, algorithm_version varchar(32) not null, content_hash varchar(64) not null, rule_set_hash varchar(64) not null, status varchar(16) not null, finding_count integer not null default 0, blocked_count integer not null default 0, warning_count integer not null default 0,
  findings jsonb not null default '[]', is_overridden boolean not null default false, override_reason text, overridden_by uuid, overridden_at timestamptz, scanned_at timestamptz not null,
  constraint ck_m6_risk_status check(status in ('CLEAN','WARNING','BLOCKED'))
);
create table geo_content_reviews (
  id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id), draft_id uuid not null references geo_content_drafts(id) on delete cascade,
  review_type varchar(32) not null, reviewer_id uuid not null, decision varchar(24) not null, comments text, reviewed_content_hash varchar(64) not null, created_at timestamptz not null,
  constraint ck_m6_review_type check(review_type in ('FACT','RISK','BUSINESS','FINAL')), constraint ck_m6_review_decision check(decision in ('APPROVED','REJECTED','CHANGES_REQUESTED'))
);
create table geo_content_publication_links (
  id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id), draft_id uuid not null references geo_content_drafts(id), publication_id uuid not null references geo_tracked_publications(id), created_at timestamptz not null, created_by uuid,
  unique(tenant_id,draft_id), unique(tenant_id,publication_id)
);
create index idx_m6_plan_merchant on geo_execution_plans(tenant_id,merchant_id,created_at desc);
create index idx_m6_work_plan on geo_execution_work_items(tenant_id,execution_plan_id,status);
create index idx_m6_draft_merchant on geo_content_drafts(tenant_id,merchant_id,status,updated_at desc);
