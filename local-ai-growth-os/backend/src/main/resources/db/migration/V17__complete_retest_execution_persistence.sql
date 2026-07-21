-- M5.2.1 execution summaries are immutable per schedule point and support idempotent replay.
create table geo_retest_execution_summaries (
 id uuid primary key, tenant_id uuid not null references tenants(id), merchant_id uuid not null references merchants(id),
 experiment_id uuid not null references geo_retest_experiments(id), schedule_point_id uuid not null references geo_retest_schedule_points(id),
 requested_sample_count integer not null default 0, successful_sample_count integer not null default 0, failed_sample_count integer not null default 0, valid_sample_count integer not null default 0,
 metric_snapshot_created boolean not null default false, attribution_draft_created boolean not null default false,
 stop_decision varchar(32) not null default 'NOT_EVALUATED', stop_reason_code varchar(120), stop_detail text, evaluated_at timestamptz,
 created_at timestamptz not null, updated_at timestamptz not null, created_by uuid, updated_by uuid,
 unique (tenant_id, schedule_point_id)
);
alter table geo_retest_metric_snapshots add column if not exists requested_sample_count integer not null default 0,
 add column if not exists successful_sample_count integer not null default 0,
 add column if not exists baseline_delta numeric(12,8), add column if not exists previous_period_delta numeric(12,8),
 add column if not exists control_delta numeric(12,8), add column if not exists adjusted_delta numeric(12,8),
 add column if not exists sample_sufficiency varchar(24) not null default 'INSUFFICIENT';

-- The V16 unique sample identity remains authoritative. These fields only lease
-- in-flight work so an expired executor can resume without concurrent calls.
alter table geo_retest_samples
  add column if not exists execution_state varchar(24) not null default 'COMPLETED',
  add column if not exists execution_lease_owner varchar(160),
  add column if not exists execution_lease_until timestamptz;
alter table geo_retest_samples
  add constraint ck_geo_retest_sample_execution_state
  check (execution_state in ('RESERVED','COMPLETED'));
create index if not exists idx_geo_retest_samples_execution_lease
  on geo_retest_samples(tenant_id, schedule_point_id, execution_state, execution_lease_until);

-- V16 created TARGET/CONTROL snapshots. M5.2.1 also persists an explicit OVERALL roll-up.
alter table geo_retest_metric_snapshots
  drop constraint if exists ck_geo_retest_metric_cohort;
alter table geo_retest_metric_snapshots
  add constraint ck_geo_retest_metric_cohort
  check (cohort in ('TARGET','CONTROL','OVERALL'));
