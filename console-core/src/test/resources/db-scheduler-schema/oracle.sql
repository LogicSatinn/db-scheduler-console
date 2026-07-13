create table scheduled_tasks (
  task_name varchar2(250) not null,
  task_instance varchar2(250) not null,
  task_data blob,
  execution_time timestamp(6) with time zone not null,
  picked number(1,0) not null,
  picked_by varchar2(50),
  last_success timestamp(6) with time zone,
  last_failure timestamp(6) with time zone,
  consecutive_failures number(10,0),
  last_heartbeat timestamp(6) with time zone,
  version number(19,0) not null,
  priority number(4,0),
  primary key (task_name, task_instance)
);

