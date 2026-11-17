-- Bootstrap public.read_maintenance_state in pocoma_read
-- Persistent maintenance authority surviving DROP SCHEMA v3_read CASCADE.
-- Idempotent, owned by admin, readable by runtime_role.

CREATE TABLE IF NOT EXISTS public.read_maintenance_state (
    id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    state VARCHAR(32) NOT NULL CHECK (state IN ('READY', 'MAINTENANCE', 'REBUILDING', 'FAILED')),
    phase VARCHAR(32) NOT NULL CHECK (phase IN (
        'IDLE', 'DRAINING', 'EXCLUDED', 'SCHEMA_DROPPED',
        'SCHEMA_CREATED', 'MIGRATED', 'ROUTED', 'REPLAYING', 'CONVERGED'
    )),
    target_prefix JSONB NULL,
    operator_id VARCHAR(64) NULL,
    operation_id UUID NULL,
    started_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    error_detail TEXT NULL
);

ALTER TABLE public.read_maintenance_state ADD COLUMN IF NOT EXISTS operation_id UUID NULL;
ALTER TABLE public.read_maintenance_state OWNER TO pocoma;
REVOKE ALL ON public.read_maintenance_state FROM PUBLIC;
REVOKE ALL ON public.read_maintenance_state FROM pocoma_read;
GRANT SELECT ON public.read_maintenance_state TO pocoma_read;

INSERT INTO public.read_maintenance_state (id, state, phase, updated_at)
VALUES (1, 'READY', 'IDLE', clock_timestamp())
ON CONFLICT (id) DO NOTHING;
