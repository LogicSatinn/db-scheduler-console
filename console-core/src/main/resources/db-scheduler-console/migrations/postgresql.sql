create table dsc_execution_history (
  id bigint generated always as identity primary key,
  task_name text not null,
  task_instance text not null,
  outcome varchar(16) not null,
  started_at timestamp with time zone not null,
  finished_at timestamp with time zone not null,
  duration_ms bigint not null,
  exception_class varchar(512),
  exception_message varchar(2000),
  stacktrace text,
  picked_by varchar(255),
  task_data bytea,
  task_data_type varchar(512)
);

create index dsc_eh_task_time_idx on dsc_execution_history (task_name, started_at);
create index dsc_eh_time_idx on dsc_execution_history (started_at);
create index dsc_eh_outcome_time_idx on dsc_execution_history (outcome, started_at);
create index dsc_eh_finished_idx on dsc_execution_history (finished_at);

create table dsc_failed_execution (
  task_name text not null,
  task_instance text not null,
  task_data bytea,
  task_data_type varchar(512),
  priority smallint not null,
  failure_count integer not null,
  failed_at timestamp with time zone not null,
  picked_by varchar(255),
  exception_class varchar(512),
  exception_message varchar(2000),
  stacktrace text,
  primary key (task_name, task_instance)
);

create index dsc_fe_failed_at_idx on dsc_failed_execution (failed_at);
