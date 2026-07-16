alter table prompt_cases add column category varchar(40);
alter table prompt_cases add column city varchar(80);
alter table prompt_cases add column district varchar(80);
alter table prompt_cases add column intent_level varchar(20) not null default 'HIGH';
alter table prompt_cases add column sort_order integer not null default 0;
update prompt_cases set category = coalesce(intent, 'RECOMMENDATION') where category is null;
alter table prompt_cases alter column category set not null;

create table evidence_observations (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  merchant_id uuid not null references merchants(id),
  prompt_case_id uuid not null references prompt_cases(id),
  platform varchar(100) not null,
  model_name varchar(120) not null,
  source_type varchar(40) not null,
  search_enabled boolean not null,
  raw_answer text not null,
  captured_at timestamp with time zone not null,
  citation_links text not null default '',
  screenshot_asset_id varchar(120),
  operator_notes text not null default '',
  brand_mention_count integer not null,
  brand_first_position integer,
  competitor_summary text not null default '{}',
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  created_by uuid,
  updated_by uuid,
  version bigint not null
);
create index idx_evidence_observations_merchant_source on evidence_observations(tenant_id, merchant_id, source_type, captured_at desc);
