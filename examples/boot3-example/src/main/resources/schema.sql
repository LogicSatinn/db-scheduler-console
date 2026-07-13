create table scheduled_tasks (
  task_name varchar(250) not null,
  task_instance varchar(250) not null,
  task_data blob,
  execution_time timestamp with time zone not null,
  picked boolean not null,
  picked_by varchar(50),
  last_success timestamp with time zone,
  last_failure timestamp with time zone,
  consecutive_failures int,
  last_heartbeat timestamp with time zone,
  version bigint not null,
  priority smallint,
  primary key (task_name, task_instance)
);

create table dsc_execution_history (
  id bigint generated always as identity primary key,
  task_name varchar(250) not null,
  task_instance varchar(250) not null,
  outcome varchar(16) not null,
  started_at timestamp with time zone not null,
  finished_at timestamp with time zone not null,
  duration_ms bigint not null,
  exception_class varchar(512),
  exception_message varchar(2000),
  stacktrace clob,
  picked_by varchar(255)
);

create index dsc_eh_task_time_idx on dsc_execution_history (task_name, started_at);
create index dsc_eh_time_idx on dsc_execution_history (started_at);
create index dsc_eh_outcome_time_idx on dsc_execution_history (outcome, started_at);
