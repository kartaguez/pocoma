-- Validation intentionally creates no Event ConsumptionSlot. Existing materializations are lazily adopted
-- by the new worker so provenance is written by the authoritative consumption transaction.
do $$
begin
    if exists (
        select 1 from consumption_slots slot
        where slot.consumable_type = 'EVENT'
          and (jsonb_array_length(slot.consumable_components) <> 1
               or slot.consumer_type <> 'PIPELINE'
               or jsonb_array_length(slot.consumer_components) <> 2
               or nullif(slot.consumer_components ->> 0, '') is null
               or not pg_input_is_valid(slot.consumer_components ->> 1, 'integer')
               or (slot.consumer_components ->> 1)::integer < 1)
    ) then
        raise exception 'An Event ConsumptionSlot has an ambiguous consumer identity';
    end if;
end $$;
