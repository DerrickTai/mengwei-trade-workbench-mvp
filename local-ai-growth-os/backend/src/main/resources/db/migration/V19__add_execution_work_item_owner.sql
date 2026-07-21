-- V18 is immutable; M6.0 work-item API persists the explicitly supported assignee field.
alter table geo_execution_work_items add column owner_name varchar(128);
