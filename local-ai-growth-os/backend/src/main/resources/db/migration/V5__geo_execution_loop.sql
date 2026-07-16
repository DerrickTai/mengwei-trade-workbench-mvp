create table optimization_tasks (
  id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id), diagnostic_run_id uuid not null references diagnostic_runs(id),
  task_type varchar(40) not null, title varchar(240) not null, description text not null, priority varchar(20) not null, target_question_ids jsonb not null default '[]', recommended_channels jsonb not null default '[]',
  assignee uuid references users(id), due_date date, status varchar(30) not null default 'TODO', completed_at timestamptz, created_at timestamptz not null, updated_at timestamptz not null, created_by uuid, updated_by uuid, version bigint not null default 0
);
create index idx_optimization_tasks_merchant on optimization_tasks(tenant_id, merchant_id, due_date);
create table content_assets (
  id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id), source_task_id uuid not null references optimization_tasks(id), channel varchar(30) not null,
  title varchar(240) not null, content text not null, fact_snapshot jsonb not null default '{}', prompt_version varchar(40) not null, generation_version varchar(40) not null, human_edited_content text, risk_flags jsonb not null default '[]', review_status varchar(30) not null default 'DRAFT', scheduled_at timestamptz,
  created_at timestamptz not null, updated_at timestamptz not null, created_by uuid, updated_by uuid, version bigint not null default 0
);
create index idx_content_assets_calendar on content_assets(tenant_id, merchant_id, scheduled_at);
create table content_publications (
  id uuid primary key, tenant_id uuid not null references tenants(id), content_asset_id uuid not null references content_assets(id), platform varchar(30) not null, account_name varchar(160) not null, published_url varchar(800), published_at timestamptz not null, screenshot_asset_id uuid references assets(id), index_status varchar(30) not null default 'UNKNOWN', notes text, created_at timestamptz not null, updated_at timestamptz not null, created_by uuid, updated_by uuid, version bigint not null default 0
);
create table retest_plans (
  id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id), diagnostic_run_id uuid not null references diagnostic_runs(id), label varchar(40) not null, planned_at date not null, status varchar(30) not null default 'PLANNED', created_at timestamptz not null, updated_at timestamptz not null, created_by uuid, updated_by uuid, version bigint not null default 0
);
