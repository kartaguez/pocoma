# Pocoma agent instructions

## Mandatory verification policy

Before planning, implementing, or verifying any change under `app/`, read:

`app/docs/testing/Reactor_Verification_Policy.md`

That document is normative.

In particular:

- Do not run the full Maven reactor by default.
- Determine and declare the verification slice(s) before implementation.
- Use the canonical Maven command defined for each slice.
- Do not add `architecture-tests` unless the policy requires a global architecture gate.
- Do not run historical database migrations by default when a trusted baseline is available.
- Escalate verification scope only when the change actually crosses a declared boundary.
- A full reactor is a global integration proof, not the default local proof.

Every implementation report must state:
- declared verification slice(s);
- commands actually executed;
- any unexpected slice crossing;
- whether a global gate was required;
- whether the full reactor was run and why.