-- Validate EPT Event materialization slots. Historical consumers are allowed.
do $$
begin
    if exists (
        select 1
        from consumption_slots slot
        where slot.consumable_type = 'EVENT'
          and slot.consumer_type = 'PROJECTION_TASK_MATERIALIZER'
          and (jsonb_array_length(slot.consumable_components) <> 1
               or nullif(slot.consumable_components ->> 0, '') is null
               or jsonb_array_length(slot.consumer_components) <> 1
               or nullif(slot.consumer_components ->> 0, '') is null)
    ) then
        raise exception 'An Event materializer ConsumptionSlot has an invalid identity';
    end if;
end $$;
