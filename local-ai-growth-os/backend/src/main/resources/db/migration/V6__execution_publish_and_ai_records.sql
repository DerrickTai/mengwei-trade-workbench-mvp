alter table content_publications add column if not exists publish_status varchar(30) not null default 'PENDING';
create table ai_generation_records (
  id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id), source_task_id uuid references optimization_tasks(id), content_asset_id uuid references content_assets(id), provider varchar(60) not null, model varchar(120) not null, prompt_version varchar(60) not null, input_facts jsonb not null default '{}', generated_content text not null, token_usage bigint, created_at timestamptz not null, created_by uuid
);
