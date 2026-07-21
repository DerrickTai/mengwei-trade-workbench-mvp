-- M5.2 automated retest scheduling, repeated samples, statistics and attribution drafts.
-- Review against the repository's final V15 before merging. Do not edit historical migrations.

alter table geo_retest_experiments
  add column if not exists automation_enabled boolean not null default false,
  add column if not exists timezone varchar(80) not null default 'UTC',
  add column if not exists schedule_template jsonb not null default '{"dayOffsets":[3,7,14,30],"localTime":"09:00"}',
  add column if not exists sample_count_per_cell integer not null default 5,
  add column if not exists collector_config_ids jsonb not null default '[]',
  add column if not exists success_criteria jsonb not null default '{}',
  add column if not exists stop_policy jsonb not null default '{}',
  add column if not exists max_api_calls integer,
  add column if not exists max_cost_micros bigint,
  add column if not exists api_calls_used integer not null default 0,
  add column if not exists cost_micros_used bigint not null default 0,
  add column if not exists next_due_at timestamptz,
  add column if not exists last_evaluated_at timestamptz,
  add column if not exists automation_state varchar(32) not null default 'MANUAL',
  add column if not exists baseline_quality varchar(32) not null default 'SNAPSHOT_PRE_PUBLICATION';

alter table geo_retest_experiments
  drop constraint if exists ck_geo_retest_sample_count_per_cell;
alter table geo_retest_experiments
  add constraint ck_geo_retest_sample_count_per_cell
  check (sample_count_per_cell between 1 and 20);

alter table geo_retest_experiments
  drop constraint if exists ck_geo_retest_automation_state;
alter table geo_retest_experiments
  add constraint ck_geo_retest_automation_state
  check (automation_state in (
    'MANUAL','NEEDS_BASELINE','READY','ACTIVE','PAUSED','STOPPED','COMPLETED','FAILED'
  ));

alter table geo_retest_experiments
  drop constraint if exists ck_geo_retest_baseline_quality;
alter table geo_retest_experiments
  add constraint ck_geo_retest_baseline_quality
  check (baseline_quality in (
    'SNAPSHOT_PRE_PUBLICATION','AUTOMATED_PRE_PUBLICATION','WEAK_POST_PUBLICATION','UNAVAILABLE'
  ));

create table geo_retest_experiment_questions (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  experiment_id uuid not null references geo_retest_experiments(id) on delete cascade,
  question_id uuid not null references consumer_questions(id),
  cohort varchar(16) not null,
  weight numeric(8,4) not null default 1.0,
  enabled boolean not null default true,
  created_at timestamptz not null,
  created_by uuid,
  constraint ck_geo_retest_question_cohort check (cohort in ('TARGET','CONTROL')),
  constraint ck_geo_retest_question_weight check (weight > 0),
  unique (tenant_id,experiment_id,question_id)
);
create index idx_geo_retest_questions_experiment
  on geo_retest_experiment_questions(tenant_id,merchant_id,experiment_id,cohort,enabled);

create table geo_retest_schedule_points (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  experiment_id uuid not null references geo_retest_experiments(id) on delete cascade,
  phase varchar(16) not null,
  day_offset integer not null,
  sequence_no integer not null,
  due_at timestamptz not null,
  status varchar(24) not null default 'PLANNED',
  attempt_count integer not null default 0,
  max_attempts integer not null default 3,
  lease_owner varchar(160),
  lease_until timestamptz,
  started_at timestamptz,
  finished_at timestamptz,
  collection_run_ids jsonb not null default '[]',
  error_summary text,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid,
  version bigint not null default 0,
  constraint ck_geo_retest_schedule_phase check (phase in ('BASELINE','RETEST')),
  constraint ck_geo_retest_schedule_status check (status in (
    'PLANNED','RUNNING','COMPLETED','PARTIAL','FAILED','SKIPPED','CANCELLED'
  )),
  constraint ck_geo_retest_schedule_attempts check (
    attempt_count >= 0 and max_attempts between 1 and 10
  ),
  unique (tenant_id,experiment_id,phase,day_offset)
);
create index idx_geo_retest_schedule_due
  on geo_retest_schedule_points(status,due_at,lease_until);
create index idx_geo_retest_schedule_experiment
  on geo_retest_schedule_points(tenant_id,merchant_id,experiment_id,sequence_no);

create table geo_retest_samples (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  experiment_id uuid not null references geo_retest_experiments(id) on delete cascade,
  schedule_point_id uuid not null references geo_retest_schedule_points(id) on delete cascade,
  question_id uuid not null references consumer_questions(id),
  cohort varchar(16) not null,
  collector_config_id uuid not null references geo_collector_configs(id),
  repetition_no integer not null,
  collection_run_id uuid references geo_collection_runs(id),
  collection_result_id uuid references geo_collection_results(id),
  observation_id uuid references ai_observations(id),
  status varchar(24) not null,
  verification_status varchar(24) not null default 'DRAFT',
  ai_platform varchar(40),
  provider_code varchar(40),
  provider_model varchar(120),
  collection_channel varchar(40),
  location_text varchar(240),
  context_sha256 varchar(64),
  merchant_mentioned boolean,
  merchant_recommended boolean,
  recommendation_rank integer,
  any_citation boolean,
  direct_publication_citation boolean,
  latency_ms bigint,
  cost_micros bigint,
  error_code varchar(120),
  error_message text,
  observed_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint ck_geo_retest_sample_cohort check (cohort in ('TARGET','CONTROL')),
  constraint ck_geo_retest_sample_status check (status in ('SUCCESS','FAILED','SKIPPED')),
  constraint ck_geo_retest_sample_repetition check (repetition_no between 1 and 20),
  constraint ck_geo_retest_sample_rank check (recommendation_rank is null or recommendation_rank > 0),
  unique (tenant_id,schedule_point_id,question_id,collector_config_id,repetition_no)
);
create index idx_geo_retest_samples_point
  on geo_retest_samples(tenant_id,merchant_id,schedule_point_id,status);
create index idx_geo_retest_samples_context
  on geo_retest_samples(tenant_id,experiment_id,context_sha256);

create table geo_retest_metric_snapshots (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  experiment_id uuid not null references geo_retest_experiments(id) on delete cascade,
  schedule_point_id uuid not null references geo_retest_schedule_points(id) on delete cascade,
  cohort varchar(16) not null,
  metric_name varchar(64) not null,
  numerator numeric(18,6),
  denominator integer not null,
  metric_value numeric(12,8),
  ci_low numeric(12,8),
  ci_high numeric(12,8),
  volatility_score numeric(12,8),
  valid_sample_count integer not null,
  failed_sample_count integer not null default 0,
  context_drift boolean not null default false,
  metadata jsonb not null default '{}',
  calculated_at timestamptz not null,
  created_at timestamptz not null,
  constraint ck_geo_retest_metric_cohort check (cohort in ('TARGET','CONTROL')),
  constraint ck_geo_retest_metric_name check (metric_name in (
    'BRAND_MENTION_RATE','BRAND_RECOMMENDATION_RATE','TOP3_RATE',
    'ANY_CITATION_RATE','DIRECT_PUBLICATION_CITATION_RATE','MEAN_RECIPROCAL_RANK'
  )),
  constraint ck_geo_retest_metric_rate check (
    metric_value is null or (metric_value >= 0 and metric_value <= 1)
  ),
  unique (tenant_id,schedule_point_id,cohort,metric_name)
);
create index idx_geo_retest_metrics_experiment
  on geo_retest_metric_snapshots(tenant_id,merchant_id,experiment_id,calculated_at desc);

create table geo_retest_attribution_assessments (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  experiment_id uuid not null references geo_retest_experiments(id) on delete cascade,
  schedule_point_id uuid references geo_retest_schedule_points(id) on delete cascade,
  result_status varchar(32) not null,
  attribution_level varchar(40) not null,
  evidence_score integer not null,
  review_status varchar(24) not null default 'DRAFT',
  primary_metric varchar(64) not null default 'BRAND_MENTION_RATE',
  target_before numeric(12,8),
  target_after numeric(12,8),
  control_before numeric(12,8),
  control_after numeric(12,8),
  target_delta numeric(12,8),
  control_delta numeric(12,8),
  adjusted_delta numeric(12,8),
  sample_count integer not null default 0,
  consecutive_improved_cycles integer not null default 0,
  context_drift boolean not null default false,
  direct_citation_verified boolean not null default false,
  reason_codes jsonb not null default '[]',
  safe_summary text not null,
  evidence_snapshot jsonb not null default '{}',
  reviewed_by uuid,
  reviewed_at timestamptz,
  review_note text,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint ck_geo_retest_assessment_status check (result_status in (
    'IMPROVED','DECLINED','STABLE','INSUFFICIENT_SAMPLE','HIGH_VOLATILITY'
  )),
  constraint ck_geo_retest_assessment_level check (attribution_level in (
    'DIRECT_CITATION','STRONG_ASSOCIATION','TEMPORAL_ASSOCIATION','INSUFFICIENT_EVIDENCE'
  )),
  constraint ck_geo_retest_assessment_review check (review_status in (
    'DRAFT','VERIFIED','REJECTED'
  )),
  constraint ck_geo_retest_assessment_score check (evidence_score between 0 and 100)
);
create index idx_geo_retest_assessment_experiment
  on geo_retest_attribution_assessments(tenant_id,merchant_id,experiment_id,created_at desc);

