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
  picked_by nvarchar(255)
);

create index dsc_eh_task_time_idx on dsc_execution_history (task_name, started_at);
create index dsc_eh_time_idx on dsc_execution_history (started_at);
create index dsc_eh_outcome_time_idx on dsc_execution_history (outcome, started_at);
