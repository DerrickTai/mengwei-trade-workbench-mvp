create table geo_collector_configs (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  name varchar(120) not null,
  ai_platform varchar(40) not null,
  collection_channel varchar(40) not null,
  provider_code varchar(40) not null,
  api_base_url text,
  model_name varchar(120),
  secret_env_name varchar(120),
  web_search_enabled boolean not null default false,
  location_country varchar(8),
  location_text varchar(240),
  request_options jsonb not null default '{}',
  auto_create_draft boolean not null default true,
  enabled boolean not null default true,
  schedule_cron varchar(120),
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid,
  version bigint not null default 0,
  constraint ck_geo_collector_channel check (
    collection_channel in (
      'OFFICIAL_API','AUTHORIZED_SEARCH_API','USER_BROWSER_CAPTURE','MANUAL_APP','MANUAL_WEB'
    )
  ),
  unique (tenant_id,merchant_id,name)
);
create index idx_geo_collector_configs_enabled
  on geo_collector_configs(tenant_id,merchant_id,enabled,ai_platform);

create table geo_collection_runs (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  collector_config_ids jsonb not null default '[]',
  question_ids jsonb not null default '[]',
  trigger_type varchar(24) not null,
  status varchar(24) not null,
  input_snapshot jsonb not null default '{}',
  total_count integer not null default 0,
  success_count integer not null default 0,
  failure_count integer not null default 0,
  started_at timestamptz,
  finished_at timestamptz,
  error_summary text,
  created_at timestamptz not null,
  created_by uuid,
  constraint ck_geo_collection_trigger check (
    trigger_type in ('MANUAL','SCHEDULED','RETEST')
  ),
  constraint ck_geo_collection_run_status check (
    status in ('QUEUED','RUNNING','PARTIAL','COMPLETED','FAILED','CANCELLED')
  )
);
create index idx_geo_collection_runs_merchant
  on geo_collection_runs(tenant_id,merchant_id,created_at desc);

create table geo_collection_results (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  run_id uuid not null references geo_collection_runs(id),
  collector_config_id uuid not null references geo_collector_configs(id),
  storefront_id uuid references storefronts(id),
  question_id uuid not null references consumer_questions(id),
  ai_platform varchar(40) not null,
  collection_channel varchar(40) not null,
  provider_code varchar(40) not null,
  provider_model varchar(120),
  provider_request_id varchar(240),
  location_text varchar(240),
  observed_at timestamptz,
  latency_ms bigint,
  status varchar(24) not null,
  raw_answer text,
  raw_response jsonb,
  cited_sources jsonb not null default '[]',
  extraction_snapshot jsonb not null default '{}',
  merchant_mentioned boolean,
  merchant_recommended boolean,
  recommendation_rank integer,
  fact_check_status varchar(24) not null default 'NOT_CHECKED',
  verification_status varchar(24) not null default 'DRAFT',
  promotion_status varchar(24) not null default 'PENDING',
  promoted_observation_id uuid references ai_observations(id),
  response_sha256 varchar(64),
  error_code varchar(120),
  error_message text,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid,
  version bigint not null default 0,
  constraint ck_geo_collection_result_status check (status in ('SUCCESS','FAILED')),
  constraint ck_geo_collection_promotion_status check (
    promotion_status in ('PENDING','PROMOTED','REJECTED')
  ),
  constraint ck_geo_collection_verification check (verification_status='DRAFT'),
  constraint ck_geo_collection_rank check (
    recommendation_rank is null or recommendation_rank > 0
  )
);
create index idx_geo_collection_results_run
  on geo_collection_results(tenant_id,merchant_id,run_id,created_at);
create index idx_geo_collection_results_question
  on geo_collection_results(tenant_id,merchant_id,question_id,observed_at desc);
create unique index uq_geo_collection_result_promoted_observation
  on geo_collection_results(tenant_id,promoted_observation_id)
  where promoted_observation_id is not null;

alter table ai_observations
  add column if not exists collection_result_id uuid references geo_collection_results(id),
  add column if not exists provider_code varchar(40),
  add column if not exists provider_model varchar(120),
  add column if not exists provider_request_id varchar(240),
  add column if not exists response_sha256 varchar(64);
create unique index if not exists uq_ai_observations_collection_result
  on ai_observations(tenant_id,collection_result_id)
  where collection_result_id is not null;

create table geo_source_evidence (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  normalized_url text not null,
  canonical_url text,
  final_url text,
  domain varchar(255) not null,
  source_type varchar(40) not null default 'UNKNOWN',
  ownership_type varchar(40) not null default 'THIRD_PARTY',
  evidence_grade varchar(24) not null default 'D',
  fetch_status varchar(24) not null default 'PENDING',
  http_status integer,
  title text,
  author text,
  publisher text,
  published_at timestamptz,
  fetched_at timestamptz,
  content_sha256 varchar(64),
  extraction_method varchar(40),
  fact_consistency_status varchar(32) not null default 'NOT_CHECKED',
  cross_source_count integer not null default 0,
  manual_review_status varchar(24) not null default 'UNREVIEWED',
  metadata jsonb not null default '{}',
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid,
  version bigint not null default 0,
  constraint ck_geo_source_ownership check (
    ownership_type in ('OWNED','COMPETITOR','THIRD_PARTY','UNKNOWN')
  ),
  constraint ck_geo_source_evidence_grade check (
    evidence_grade in ('A','B','C','D','CONFLICTED','UNAVAILABLE')
  ),
  constraint ck_geo_source_fetch_status check (
    fetch_status in ('PENDING','FETCHED','FAILED','BLOCKED')
  ),
  constraint ck_geo_source_fact_status check (
    fact_consistency_status in ('NOT_CHECKED','CONSISTENT','CONFLICTED','PARTIAL')
  ),
  constraint ck_geo_source_review_status check (
    manual_review_status in ('UNREVIEWED','VERIFIED','DISPUTED')
  ),
  unique (tenant_id,merchant_id,normalized_url)
);
create index idx_geo_source_evidence_domain
  on geo_source_evidence(tenant_id,merchant_id,domain);

create table geo_observation_source_links (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  observation_id uuid references ai_observations(id),
  collection_result_id uuid references geo_collection_results(id),
  source_evidence_id uuid not null references geo_source_evidence(id),
  citation_order integer,
  first_seen_at timestamptz not null,
  last_seen_at timestamptz not null,
  created_at timestamptz not null,
  created_by uuid,
  constraint ck_geo_source_link_owner check (
    (observation_id is not null and collection_result_id is null)
    or (observation_id is null and collection_result_id is not null)
  )
);
create unique index uq_geo_observation_source_link_observation
  on geo_observation_source_links(tenant_id,observation_id,source_evidence_id)
  where observation_id is not null;
create unique index uq_geo_observation_source_link_result
  on geo_observation_source_links(tenant_id,collection_result_id,source_evidence_id)
  where collection_result_id is not null;

create table geo_tracked_publications (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  optimization_task_id uuid references optimization_tasks(id),
  platform varchar(40) not null,
  title text not null,
  normalized_url text not null,
  canonical_url text,
  domain varchar(255) not null,
  published_at timestamptz,
  content_sha256 varchar(64),
  status varchar(24) not null default 'ACTIVE',
  metadata jsonb not null default '{}',
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid,
  version bigint not null default 0,
  constraint ck_geo_publication_status check (
    status in ('DRAFT','ACTIVE','ARCHIVED','REMOVED')
  ),
  unique (tenant_id,merchant_id,normalized_url)
);
create index idx_geo_tracked_publications_task
  on geo_tracked_publications(tenant_id,merchant_id,optimization_task_id);

create table geo_publication_citation_events (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  publication_id uuid not null references geo_tracked_publications(id),
  observation_id uuid references ai_observations(id),
  collection_result_id uuid references geo_collection_results(id),
  question_id uuid references consumer_questions(id),
  ai_platform varchar(40) not null,
  match_type varchar(40) not null,
  confidence numeric(5,4) not null,
  direct_citation boolean not null,
  citation_count integer not null default 1,
  first_seen_at timestamptz not null,
  last_seen_at timestamptz not null,
  evidence jsonb not null default '{}',
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid,
  constraint ck_geo_publication_match_type check (
    match_type in (
      'EXACT_URL','REDIRECTED_URL','CANONICAL_URL',
      'CONTENT_FINGERPRINT','DOMAIN_ONLY','MANUAL_CONFIRMED'
    )
  ),
  constraint ck_geo_publication_source check (
    observation_id is not null or collection_result_id is not null
  )
);
create unique index uq_geo_publication_event_observation
  on geo_publication_citation_events(tenant_id,publication_id,observation_id,match_type)
  where observation_id is not null;
create unique index uq_geo_publication_event_result
  on geo_publication_citation_events(tenant_id,publication_id,collection_result_id,match_type)
  where collection_result_id is not null;

create table geo_retest_experiments (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  name varchar(160) not null,
  baseline_snapshot_id uuid not null references geo_diagnosis_snapshots(id),
  intervention_task_id uuid references optimization_tasks(id),
  status varchar(24) not null default 'PLANNED',
  question_ids jsonb not null default '[]',
  ai_platforms jsonb not null default '[]',
  collection_channels jsonb not null default '[]',
  location_text varchar(240),
  repetitions integer not null default 3,
  comparison_options jsonb not null default '{}',
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid,
  version bigint not null default 0,
  constraint ck_geo_retest_status check (
    status in ('PLANNED','RUNNING','COMPLETED','CANCELLED')
  ),
  constraint ck_geo_retest_repetitions check (repetitions between 1 and 20)
);
create index idx_geo_retest_experiments_merchant
  on geo_retest_experiments(tenant_id,merchant_id,created_at desc);

create table geo_retest_runs (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  experiment_id uuid not null references geo_retest_experiments(id),
  phase varchar(24) not null,
  collection_run_id uuid references geo_collection_runs(id),
  diagnosis_snapshot_id uuid references geo_diagnosis_snapshots(id),
  sample_count integer not null default 0,
  metrics_snapshot jsonb not null default '{}',
  source_snapshot jsonb not null default '{}',
  comparison_snapshot jsonb not null default '{}',
  result_status varchar(32),
  attribution_level varchar(40),
  run_at timestamptz not null,
  created_at timestamptz not null,
  created_by uuid,
  constraint ck_geo_retest_phase check (phase in ('BASELINE','RETEST')),
  constraint ck_geo_retest_result_status check (
    result_status is null or result_status in (
      'IMPROVED','DECLINED','STABLE','INSUFFICIENT_SAMPLE','HIGH_VOLATILITY'
    )
  ),
  constraint ck_geo_retest_attribution check (
    attribution_level is null or attribution_level in (
      'DIRECT_CITATION','STRONG_ASSOCIATION',
      'TEMPORAL_ASSOCIATION','INSUFFICIENT_EVIDENCE'
    )
  )
);
create index idx_geo_retest_runs_experiment
  on geo_retest_runs(tenant_id,merchant_id,experiment_id,run_at desc);
