do $$
declare
    foreign_key record;
begin
    for foreign_key in
        select constraint_definition.conname, relation.relname
        from pg_constraint constraint_definition
        join pg_class relation on relation.oid = constraint_definition.conrelid
        where constraint_definition.contype = 'f'
          and constraint_definition.confrelid = 'projection_coverages'::regclass
    loop
        execute format('alter table %I drop constraint %I', foreign_key.relname, foreign_key.conname);
    end loop;
end $$;

alter table projection_artifacts
    add check (projection_type <> '' and pipeline_id <> '' and pipeline_version >= 1);
alter table projection_failures
    add check (projection_type <> '' and pipeline_id <> '' and pipeline_version >= 1);
alter table projection_heads
    add check (projection_type <> '' and pipeline_id <> '' and pipeline_version >= 1);

drop table projection_coverages;
