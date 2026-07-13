create table scheduled_tasks (
  task_name varchar(250) not null,
  task_instance varchar(250) not null,
  task_data blob,
  execution_time timestamp(6) not null,
  picked boolean not null,
  picked_by varchar(50),
  last_success timestamp(6) null,
  last_failure timestamp(6) null,
  consecutive_failures int,
  last_heartbeat timestamp(6) null,
  version bigint not null,
  priority smallint,
  primary key (task_name, task_instance)
);
