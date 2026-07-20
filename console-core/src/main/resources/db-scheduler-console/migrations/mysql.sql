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
  picked_by varchar(255),
  task_data longblob,
  task_data_type varchar(512)
);

create index dsc_eh_task_time_idx on dsc_execution_history (task_name, started_at);
create index dsc_eh_time_idx on dsc_execution_history (started_at);
create index dsc_eh_outcome_time_idx on dsc_execution_history (outcome, started_at);
create index dsc_eh_finished_idx on dsc_execution_history (finished_at);

create table dsc_failed_execution (
  task_name varchar(250) not null,
  task_instance varchar(250) not null,
  task_data longblob,
  task_data_type varchar(512),
  priority smallint not null,
  failure_count int not null,
  failed_at timestamp(6) not null,
  picked_by varchar(255),
  exception_class varchar(512),
  exception_message varchar(2000),
  stacktrace mediumtext,
  primary key (task_name, task_instance)
);

create index dsc_fe_failed_at_idx on dsc_failed_execution (failed_at);
