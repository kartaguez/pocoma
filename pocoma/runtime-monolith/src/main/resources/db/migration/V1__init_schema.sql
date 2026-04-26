create table pot_global_versions (
    pot_id uuid not null,
    version bigint not null,
    primary key (pot_id)
);

create table pot_headers (
    id uuid not null,
    pot_id uuid not null,
    started_at_version bigint not null,
    ended_at_version bigint,
    label varchar(255) not null,
    creator_id uuid not null,
    deleted boolean not null,
    primary key (id)
);

create table shareholders (
    id uuid not null,
    shareholder_id uuid not null,
    pot_id uuid not null,
    started_at_version bigint not null,
    ended_at_version bigint,
    name varchar(255) not null,
    weight_numerator bigint not null,
    weight_denominator bigint not null,
    user_id uuid,
    deleted boolean not null,
    primary key (id)
);

create table expense_headers (
    id uuid not null,
    expense_id uuid not null,
    pot_id uuid not null,
    started_at_version bigint not null,
    ended_at_version bigint,
    payer_id uuid not null,
    amount_numerator bigint not null,
    amount_denominator bigint not null,
    label varchar(255) not null,
    deleted boolean not null,
    primary key (id)
);

create table expense_shares (
    id uuid not null,
    expense_id uuid not null,
    shareholder_id uuid not null,
    pot_id uuid not null,
    started_at_version bigint not null,
    ended_at_version bigint,
    weight_numerator bigint not null,
    weight_denominator bigint not null,
    primary key (id)
);

create table pot_balance_projection_states (
    pot_id uuid not null,
    projected_version bigint not null,
    primary key (pot_id)
);

create table pot_balance_versions (
    id uuid not null,
    pot_id uuid not null,
    version bigint not null,
    primary key (id),
    constraint uk_pot_balance_versions_pot_id_version unique (pot_id, version)
);

create table pot_balances (
    id uuid not null,
    pot_id uuid not null,
    version bigint not null,
    shareholder_id uuid not null,
    value_numerator bigint not null,
    value_denominator bigint not null,
    primary key (id),
    constraint uk_pot_balances_pot_id_version_shareholder_id unique (pot_id, version, shareholder_id)
);

create index idx_pot_headers_pot_version
    on pot_headers (pot_id, started_at_version, ended_at_version);

create index idx_shareholders_pot_version
    on shareholders (pot_id, started_at_version, ended_at_version);

create index idx_shareholders_user_pot
    on shareholders (user_id, pot_id);

create index idx_expense_headers_expense_version
    on expense_headers (expense_id, started_at_version, ended_at_version);

create index idx_expense_headers_pot_version
    on expense_headers (pot_id, started_at_version, ended_at_version);

create index idx_expense_shares_expense_version
    on expense_shares (expense_id, started_at_version, ended_at_version);

create index idx_expense_shares_pot_version
    on expense_shares (pot_id, started_at_version, ended_at_version);

create index idx_pot_balance_versions_pot_version
    on pot_balance_versions (pot_id, version);

create index idx_pot_balances_pot_version
    on pot_balances (pot_id, version);
