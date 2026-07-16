alter table ai_generation_records add column if not exists missing_facts jsonb not null default '[]';
alter table ai_generation_records add column if not exists risk_flags jsonb not null default '[]';
alter table ai_generation_records add column if not exists execution_status varchar(30) not null default 'SUCCEEDED';
alter table ai_generation_records add column if not exists error_message text;
