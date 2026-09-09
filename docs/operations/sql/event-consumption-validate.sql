-- Validate only catalog-driven Event scheduling slots; other independent Event consumers are allowed.
do $$
begin
    if exists (
        select 1 from consumption_slots slot
        where slot.consumable_type = 'EVENT'
          and slot.consumer_type = 'PROJECTION_TASK_SCHEDULER'
          and (jsonb_array_length(slot.consumable_components) <> 1
               or jsonb_array_length(slot.consumer_components) <> 2
               or nullif(slot.consumer_components ->> 0, '') is null
               or not pg_input_is_valid(slot.consumer_components ->> 1, 'integer')
               or (slot.consumer_components ->> 1)::integer < 1)
    ) then
        raise exception 'An Event scheduler ConsumptionSlot has an invalid exact generation identity';
    end if;
end $$;
