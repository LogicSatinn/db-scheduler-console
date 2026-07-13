create table scheduled_tasks (
  task_name nvarchar(250) not null,
  task_instance nvarchar(250) not null,
  task_data varbinary(max),
  execution_time datetimeoffset not null,
  picked bit not null,
  picked_by nvarchar(50),
  last_success datetimeoffset,
  last_failure datetimeoffset,
  consecutive_failures int,
  last_heartbeat datetimeoffset,
  version bigint not null,
  priority smallint,
  primary key (task_name, task_instance)
);
