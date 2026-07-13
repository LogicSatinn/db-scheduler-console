create table dsc_execution_history (
  id number(19) generated always as identity primary key,
  task_name varchar2(250) not null,
  task_instance varchar2(250) not null,
  outcome varchar2(16) not null,
  started_at timestamp(6) with time zone not null,
  finished_at timestamp(6) with time zone not null,
  duration_ms number(19) not null,
  exception_class varchar2(512),
  exception_message varchar2(2000),
  stacktrace clob,
  picked_by varchar2(255)
);

create index dsc_eh_task_time_idx on dsc_execution_history (task_name, started_at);
create index dsc_eh_time_idx on dsc_execution_history (started_at);
create index dsc_eh_outcome_time_idx on dsc_execution_history (outcome, started_at);
