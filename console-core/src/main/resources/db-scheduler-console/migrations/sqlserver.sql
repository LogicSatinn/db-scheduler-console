create table dsc_execution_history (
  id bigint identity(1,1) primary key,
  task_name nvarchar(250) not null,
  task_instance nvarchar(250) not null,
  outcome nvarchar(16) not null,
  started_at datetimeoffset not null,
  finished_at datetimeoffset not null,
  duration_ms bigint not null,
  exception_class nvarchar(512),
  exception_message nvarchar(2000),
  stacktrace nvarchar(max),
  picked_by nvarchar(255),
  task_data varbinary(max),
  task_data_type nvarchar(512)
);

create index dsc_eh_task_time_idx on dsc_execution_history (task_name, started_at);
create index dsc_eh_time_idx on dsc_execution_history (started_at);
create index dsc_eh_outcome_time_idx on dsc_execution_history (outcome, started_at);
create index dsc_eh_finished_idx on dsc_execution_history (finished_at);

create table dsc_failed_execution (
  task_name nvarchar(250) not null,
  task_instance nvarchar(250) not null,
  task_data varbinary(max),
  task_data_type nvarchar(512),
  priority smallint not null,
  failure_count int not null,
  failed_at datetimeoffset not null,
  picked_by nvarchar(255),
  exception_class nvarchar(512),
  exception_message nvarchar(2000),
  stacktrace nvarchar(max),
  primary key (task_name, task_instance)
);

create index dsc_fe_failed_at_idx on dsc_failed_execution (failed_at);
