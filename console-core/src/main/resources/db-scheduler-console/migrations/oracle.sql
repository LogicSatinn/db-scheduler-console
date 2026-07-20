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
  picked_by varchar2(255),
  task_data blob,
  task_data_type varchar2(512)
);

create index dsc_eh_task_time_idx on dsc_execution_history (task_name, started_at);
create index dsc_eh_time_idx on dsc_execution_history (started_at);
create index dsc_eh_outcome_time_idx on dsc_execution_history (outcome, started_at);
create index dsc_eh_finished_idx on dsc_execution_history (finished_at);

create table dsc_failed_execution (
  task_name varchar2(250) not null,
  task_instance varchar2(250) not null,
  task_data blob,
  task_data_type varchar2(512),
  priority number(5) not null,
  failure_count number(10) not null,
  failed_at timestamp(6) with time zone not null,
  picked_by varchar2(255),
  exception_class varchar2(512),
  exception_message varchar2(2000),
  stacktrace clob,
  primary key (task_name, task_instance)
);

create index dsc_fe_failed_at_idx on dsc_failed_execution (failed_at);
