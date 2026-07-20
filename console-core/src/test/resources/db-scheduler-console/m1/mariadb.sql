create table dsc_execution_history (
  id bigint auto_increment primary key,
  task_name varchar(250) not null,
  task_instance varchar(250) not null,
  outcome varchar(16) not null,
  started_at timestamp(6) not null,
  finished_at timestamp(6) not null,
  duration_ms bigint not null,
  exception_class varchar(512),
  exception_message varchar(2000),
  stacktrace mediumtext,
  picked_by varchar(255)
);

create index dsc_eh_task_time_idx on dsc_execution_history (task_name, started_at);
create index dsc_eh_time_idx on dsc_execution_history (started_at);
create index dsc_eh_outcome_time_idx on dsc_execution_history (outcome, started_at);
