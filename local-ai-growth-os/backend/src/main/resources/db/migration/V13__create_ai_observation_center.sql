create table consumer_questions (
  id uuid primary key, tenant_id uuid not null, merchant_id uuid not null, storefront_id uuid,
  question_text text not null, city varchar(120), district varchar(120), industry varchar(120),
  consumer_scenario text, target_audience text, budget_text text, decision_stage varchar(30) not null,
  commercial_value int not null check (commercial_value between 1 and 5), target_platforms jsonb not null default '[]',
  enabled boolean not null default true, demo boolean not null default false, notes text,
  deleted boolean not null default false, created_at timestamptz not null, updated_at timestamptz not null,
  created_by uuid, updated_by uuid, version bigint not null default 0
);
create index idx_consumer_questions_merchant on consumer_questions(tenant_id,merchant_id,enabled,deleted);

create table ai_observations (
  id uuid primary key, tenant_id uuid not null, merchant_id uuid not null, storefront_id uuid,
  question_id uuid not null, ai_platform varchar(40) not null, observation_mode varchar(30) not null,
  observed_at timestamptz not null, location_text text, raw_answer text not null, screenshot_asset_id uuid,
  merchant_mentioned boolean not null default false, merchant_recommended boolean not null default false,
  recommendation_rank int, fact_check_status varchar(30) not null default 'NOT_CHECKED',
  cited_sources jsonb not null default '[]', mentioned_competitors jsonb not null default '[]',
  verification_status varchar(30) not null default 'DRAFT', demo boolean not null default false, notes text,
  deleted boolean not null default false, created_at timestamptz not null, updated_at timestamptz not null,
  created_by uuid, updated_by uuid, version bigint not null default 0
);
create index idx_ai_observations_merchant on ai_observations(tenant_id,merchant_id,observed_at,deleted);
create index idx_ai_observations_question on ai_observations(tenant_id,question_id,deleted);

create table ai_observation_fact_issues (
  id uuid primary key, tenant_id uuid not null, merchant_id uuid not null, observation_id uuid not null,
  fact_id uuid, fact_version_id uuid, issue_type varchar(40) not null, observed_value text not null,
  expected_value_snapshot jsonb, severity varchar(20) not null, resolved boolean not null default false,
  resolution_notes text, created_at timestamptz not null, updated_at timestamptz not null,
  created_by uuid, updated_by uuid
);
create index idx_ai_observation_fact_issues_observation on ai_observation_fact_issues(tenant_id,observation_id,resolved);
