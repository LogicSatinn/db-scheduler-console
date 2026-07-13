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
