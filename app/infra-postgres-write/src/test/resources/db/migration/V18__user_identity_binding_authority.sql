create table users (
    user_id uuid not null,
    primary key (user_id)
);

insert into users (user_id)
select distinct pocoma_user_id
from external_identities;

alter table external_identities
    add column binding_id uuid;

update external_identities
set binding_id = gen_random_uuid();

alter table external_identities
    alter column binding_id set not null;

alter table external_identities
    rename column pocoma_user_id to user_id;

alter table external_identities
    add constraint uk_external_identities_binding_id unique (binding_id),
    add constraint fk_external_identities_user
        foreign key (user_id) references users (user_id) on delete restrict;

create index idx_external_identities_user_id
    on external_identities (user_id);
