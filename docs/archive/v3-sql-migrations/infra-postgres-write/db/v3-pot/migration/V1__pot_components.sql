-- Fresh V3 development schema. Selected explicitly; never part of V2's migration chain.
create table v3_pot_identity (pot_id uuid primary key);
create table v3_pot_version (
    pot_id uuid not null references v3_pot_identity(pot_id),
    version bigint not null check (version >= 1), recorded_at timestamptz not null,
    writer_xid xid8 not null default pg_current_xact_id(),
    primary key(pot_id,version)
);
create table v3_shareholder_identity (
    pot_id uuid not null references v3_pot_identity(pot_id), object_id uuid primary key,
    created_at bigint not null check (created_at >= 1), unique(pot_id,object_id),
    foreign key(pot_id,created_at) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create table v3_expense_identity (
    pot_id uuid not null references v3_pot_identity(pot_id), object_id uuid primary key,
    created_at bigint not null check (created_at >= 1), unique(pot_id,object_id),
    foreign key(pot_id,created_at) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create table v3_pot_header (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id), object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1), active_to bigint check(active_to > active_from),
    deleted boolean not null,
    unique(pot_id,object_id,local_version), unique(pot_id,revision_id),
    check (object_id=pot_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_pot_header_open on v3_pot_header(pot_id,object_id) where active_to is null;
create index v3_pot_header_at_version on v3_pot_header(pot_id,object_id,active_from,active_to);
create table v3_pot_details (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id), object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1), active_to bigint check(active_to > active_from),
    label text not null, creator_id uuid not null,
    unique(pot_id,object_id,local_version), unique(pot_id,revision_id),
    check (object_id=pot_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_pot_details_open on v3_pot_details(pot_id,object_id) where active_to is null;
create index v3_pot_details_at_version on v3_pot_details(pot_id,object_id,active_from,active_to);
create table v3_shareholder_header (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id), object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1), active_to bigint check(active_to > active_from),
    deleted boolean not null,
    unique(pot_id,object_id,local_version), unique(pot_id,revision_id),
    foreign key(pot_id,object_id) references v3_shareholder_identity(pot_id,object_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_shareholder_header_open on v3_shareholder_header(pot_id,object_id) where active_to is null;
create index v3_shareholder_header_at_version on v3_shareholder_header(pot_id,object_id,active_from,active_to);
create table v3_shareholder_details (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id), object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1), active_to bigint check(active_to > active_from),
    name text not null, user_id uuid,
    unique(pot_id,object_id,local_version), unique(pot_id,revision_id),
    foreign key(pot_id,object_id) references v3_shareholder_identity(pot_id,object_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_shareholder_details_open on v3_shareholder_details(pot_id,object_id) where active_to is null;
create index v3_shareholder_details_at_version on v3_shareholder_details(pot_id,object_id,active_from,active_to);
create table v3_shareholder_weight (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id), object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1), active_to bigint check(active_to > active_from),
    numerator text not null check(numerator ~ '^[1-9][0-9]*$'), denominator text not null check(denominator ~ '^[1-9][0-9]*$'),
    unique(pot_id,object_id,local_version), unique(pot_id,revision_id),
    foreign key(pot_id,object_id) references v3_shareholder_identity(pot_id,object_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_shareholder_weight_open on v3_shareholder_weight(pot_id,object_id) where active_to is null;
create index v3_shareholder_weight_at_version on v3_shareholder_weight(pot_id,object_id,active_from,active_to);
create table v3_expense_header (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id), object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1), active_to bigint check(active_to > active_from),
    deleted boolean not null,
    unique(pot_id,object_id,local_version), unique(pot_id,revision_id),
    foreign key(pot_id,object_id) references v3_expense_identity(pot_id,object_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_expense_header_open on v3_expense_header(pot_id,object_id) where active_to is null;
create index v3_expense_header_at_version on v3_expense_header(pot_id,object_id,active_from,active_to);
create table v3_expense_details (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id), object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1), active_to bigint check(active_to > active_from),
    label text not null, expense_date date not null,
    unique(pot_id,object_id,local_version), unique(pot_id,revision_id),
    foreign key(pot_id,object_id) references v3_expense_identity(pot_id,object_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_expense_details_open on v3_expense_details(pot_id,object_id) where active_to is null;
create index v3_expense_details_at_version on v3_expense_details(pot_id,object_id,active_from,active_to);
create table v3_expense_financials (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id), object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1), active_to bigint check(active_to > active_from),
    numerator text not null check(numerator ~ '^(0|[1-9][0-9]*)$'), denominator text not null check(denominator ~ '^[1-9][0-9]*$'), payer_id uuid not null, foreign key(pot_id,payer_id) references v3_shareholder_identity(pot_id,object_id),
    unique(pot_id,object_id,local_version), unique(pot_id,revision_id),
    foreign key(pot_id,object_id) references v3_expense_identity(pot_id,object_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_expense_financials_open on v3_expense_financials(pot_id,object_id) where active_to is null;
create index v3_expense_financials_at_version on v3_expense_financials(pot_id,object_id,active_from,active_to);
create table v3_expense_participant (
    pot_id uuid not null, financial_revision_id bigint not null, shareholder_id uuid not null,
    numerator text, denominator text,
    primary key(financial_revision_id,shareholder_id),
    foreign key(pot_id,financial_revision_id) references v3_expense_financials(pot_id,revision_id),
    foreign key(pot_id,shareholder_id) references v3_shareholder_identity(pot_id,object_id),
    check ((numerator is null and denominator is null) or
           (numerator is not null and denominator is not null and numerator ~ '^[1-9][0-9]*$' and denominator ~ '^[1-9][0-9]*$'))
);
create index v3_participant_shareholder on v3_expense_participant(pot_id,shareholder_id,financial_revision_id);
create index v3_financial_payer on v3_expense_financials(pot_id,payer_id,active_from,active_to);

-- Caller writes may be ordered freely inside one transaction. A version cannot commit alone.
create function v3_require_effective_version() returns trigger language plpgsql as $$
begin
    if not exists (
        select 1 from v3_pot_header where pot_id=new.pot_id and active_from=new.version
        union all select 1 from v3_pot_details where pot_id=new.pot_id and active_from=new.version
        union all select 1 from v3_shareholder_header where pot_id=new.pot_id and active_from=new.version
        union all select 1 from v3_shareholder_details where pot_id=new.pot_id and active_from=new.version
        union all select 1 from v3_shareholder_weight where pot_id=new.pot_id and active_from=new.version
        union all select 1 from v3_expense_header where pot_id=new.pot_id and active_from=new.version
        union all select 1 from v3_expense_details where pot_id=new.pot_id and active_from=new.version
        union all select 1 from v3_expense_financials where pot_id=new.pot_id and active_from=new.version
    ) then
        raise exception 'V3 Pot version has no component revision' using errcode='23514';
    end if;
    return new;
end $$;
create constraint trigger v3_version_has_effect after insert on v3_pot_version
    deferrable initially deferred for each row execute function v3_require_effective_version();

create function v3_require_initial_components() returns trigger language plpgsql as $$
begin
    if tg_table_name='v3_pot_identity' then
        if not exists(select 1 from v3_pot_header where pot_id=new.pot_id and local_version=1 and active_from=1)
           or not exists(select 1 from v3_pot_details where pot_id=new.pot_id and local_version=1 and active_from=1) then
            raise exception 'Incomplete initial Pot' using errcode='23514';
        end if;
    elsif tg_table_name='v3_shareholder_identity' then
        if not exists(select 1 from v3_shareholder_header where pot_id=new.pot_id and object_id=new.object_id and local_version=1 and active_from=new.created_at)
           or not exists(select 1 from v3_shareholder_details where pot_id=new.pot_id and object_id=new.object_id and local_version=1 and active_from=new.created_at)
           or not exists(select 1 from v3_shareholder_weight where pot_id=new.pot_id and object_id=new.object_id and local_version=1 and active_from=new.created_at) then
            raise exception 'Incomplete initial Shareholder' using errcode='23514';
        end if;
    else
        if not exists(select 1 from v3_expense_header where pot_id=new.pot_id and object_id=new.object_id and local_version=1 and active_from=new.created_at)
           or not exists(select 1 from v3_expense_details where pot_id=new.pot_id and object_id=new.object_id and local_version=1 and active_from=new.created_at)
           or not exists(select 1 from v3_expense_financials where pot_id=new.pot_id and object_id=new.object_id and local_version=1 and active_from=new.created_at) then
            raise exception 'Incomplete initial Expense' using errcode='23514';
        end if;
    end if;
    return new;
end $$;
create constraint trigger v3_pot_complete after insert on v3_pot_identity
    deferrable initially deferred for each row execute function v3_require_initial_components();
create constraint trigger v3_shareholder_complete after insert on v3_shareholder_identity
    deferrable initially deferred for each row execute function v3_require_initial_components();
create constraint trigger v3_expense_complete after insert on v3_expense_identity
    deferrable initially deferred for each row execute function v3_require_initial_components();
