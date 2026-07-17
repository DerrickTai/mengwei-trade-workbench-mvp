create table geo_strategy_configs (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  brand_terms jsonb not null default '[]',
  ambiguous_terms jsonb not null default '[]',
  competitor_ids jsonb not null default '[]',
  question_ids jsonb not null default '[]',
  target_platforms jsonb not null default '[]',
  owned_domains jsonb not null default '[]',
  notes text,
  created_at timestamptz not null, updated_at timestamptz not null,
  created_by uuid, updated_by uuid, version bigint not null default 0,
  unique (tenant_id,merchant_id)
);

create table geo_diagnosis_snapshots (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  task_diagnostic_run_id uuid not null references diagnostic_runs(id),
  rule_version varchar(40) not null,
  sample_from timestamptz, sample_to timestamptz,
  observation_count integer not null,
  config_snapshot jsonb not null, metric_snapshot jsonb not null,
  platform_snapshot jsonb not null, question_snapshot jsonb not null,
  competitor_snapshot jsonb not null, source_snapshot jsonb not null,
  gap_snapshot jsonb not null, strategy_snapshot jsonb not null,
  causality_notice text not null,
  created_at timestamptz not null, created_by uuid
);
create index idx_geo_diagnosis_snapshots_merchant on geo_diagnosis_snapshots(tenant_id,merchant_id,created_at desc);

create table geo_strategy_task_links (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  snapshot_id uuid not null references geo_diagnosis_snapshots(id),
  strategy_code varchar(80) not null,
  task_id uuid not null references optimization_tasks(id),
  created_at timestamptz not null, created_by uuid,
  unique(tenant_id,snapshot_id,strategy_code)
);
create index idx_geo_strategy_task_links_task on geo_strategy_task_links(tenant_id,merchant_id,task_id);
