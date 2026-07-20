alter table dsc_execution_history add column task_data longblob;
alter table dsc_execution_history add column task_data_type varchar(512);
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
