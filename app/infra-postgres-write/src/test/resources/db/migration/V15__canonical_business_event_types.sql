do $$
begin
    if exists (
        select 1
        from business_event_outbox
        where event_type not in (
            'PotCreatedEvent', 'PotDeletedEvent', 'PotDetailsUpdatedEvent',
            'PotShareholdersAddedEvent', 'PotShareholdersDetailsUpdatedEvent',
            'PotShareholdersWeightsUpdatedEvent', 'ExpenseCreatedEvent', 'ExpenseDeletedEvent',
            'ExpenseDetailsUpdatedEvent', 'ExpenseSharesUpdatedEvent',
            'POT_CREATED', 'POT_DELETED', 'POT_DETAILS_UPDATED',
            'POT_SHAREHOLDERS_ADDED', 'POT_SHAREHOLDERS_DETAILS_UPDATED',
            'POT_SHAREHOLDERS_WEIGHTS_UPDATED', 'EXPENSE_CREATED', 'EXPENSE_DELETED',
            'EXPENSE_DETAILS_UPDATED', 'EXPENSE_SHARES_UPDATED'
        )
    ) then
        raise exception 'business_event_outbox contains an unknown event_type';
    end if;

    if exists (
        select 1
        from business_event_outbox
        where payload_json::jsonb ? 'eventType'
          and (
              jsonb_typeof(payload_json::jsonb -> 'eventType') is distinct from 'string'
              or (payload_json::jsonb ->> 'eventType') is distinct from event_type
          )
    ) then
        raise exception 'business_event_outbox contains an incoherent payload eventType';
    end if;
end $$;

update business_event_outbox
set payload_json = jsonb_set(
        payload_json::jsonb,
        '{eventType}',
        to_jsonb(case event_type
            when 'PotCreatedEvent' then 'POT_CREATED'
            when 'PotDeletedEvent' then 'POT_DELETED'
            when 'PotDetailsUpdatedEvent' then 'POT_DETAILS_UPDATED'
            when 'PotShareholdersAddedEvent' then 'POT_SHAREHOLDERS_ADDED'
            when 'PotShareholdersDetailsUpdatedEvent' then 'POT_SHAREHOLDERS_DETAILS_UPDATED'
            when 'PotShareholdersWeightsUpdatedEvent' then 'POT_SHAREHOLDERS_WEIGHTS_UPDATED'
            when 'ExpenseCreatedEvent' then 'EXPENSE_CREATED'
            when 'ExpenseDeletedEvent' then 'EXPENSE_DELETED'
            when 'ExpenseDetailsUpdatedEvent' then 'EXPENSE_DETAILS_UPDATED'
            when 'ExpenseSharesUpdatedEvent' then 'EXPENSE_SHARES_UPDATED'
            else event_type
        end),
        false
    )::text
where payload_json::jsonb ? 'eventType';

update business_event_outbox
set event_type = case event_type
    when 'PotCreatedEvent' then 'POT_CREATED'
    when 'PotDeletedEvent' then 'POT_DELETED'
    when 'PotDetailsUpdatedEvent' then 'POT_DETAILS_UPDATED'
    when 'PotShareholdersAddedEvent' then 'POT_SHAREHOLDERS_ADDED'
    when 'PotShareholdersDetailsUpdatedEvent' then 'POT_SHAREHOLDERS_DETAILS_UPDATED'
    when 'PotShareholdersWeightsUpdatedEvent' then 'POT_SHAREHOLDERS_WEIGHTS_UPDATED'
    when 'ExpenseCreatedEvent' then 'EXPENSE_CREATED'
    when 'ExpenseDeletedEvent' then 'EXPENSE_DELETED'
    when 'ExpenseDetailsUpdatedEvent' then 'EXPENSE_DETAILS_UPDATED'
    when 'ExpenseSharesUpdatedEvent' then 'EXPENSE_SHARES_UPDATED'
    else event_type
end;

alter table business_event_outbox
    add constraint ck_business_event_outbox_event_type check (event_type in (
        'POT_CREATED',
        'POT_DELETED',
        'POT_DETAILS_UPDATED',
        'POT_SHAREHOLDERS_ADDED',
        'POT_SHAREHOLDERS_DETAILS_UPDATED',
        'POT_SHAREHOLDERS_WEIGHTS_UPDATED',
        'EXPENSE_CREATED',
        'EXPENSE_DELETED',
        'EXPENSE_DETAILS_UPDATED',
        'EXPENSE_SHARES_UPDATED'
    ));
