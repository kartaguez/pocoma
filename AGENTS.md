# Pocoma agent instructions

## Mandatory verification policy

Before planning, implementing, or verifying any change under `app/`, read:

`docs/testing/Reactor_Verification_Policy.md`

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

## Code quality and discoverability

These guidelines apply to all implementations, fixes, and refactorings.

- **Readability**: Prefer code that is self-explanatory without excessive external commentary. Use precise names reflecting domain or technical responsibilities, and make invariants and non-obvious decisions explicit.
- **Organization**: Group classes by coherent responsibility, keep package boundaries intelligible, place each concept where a developer naturally expects to find it, and avoid cyclic dependencies or scattered responsibilities.
- **Design**: Favor high cohesion and limited coupling. Introduce abstractions only when backed by a real responsibility; avoid catch-all classes, domain-free generic helpers, and unnecessary indirections. Keep state, invariants, and the operations that mutate them close together.
- **Complexity**: Reduce accidental complexity. Do not artificially split methods or classes just to satisfy a line-count or metric target, and do not sacrifice global comprehension for local metrics.
- **Evolution**: Maintain or improve the readability of the modified scope, reuse relevant existing conventions, avoid opportunistic refactorings unrelated to the task, and report significant out-of-scope structural issues rather than silently fixing them.
- **Verification**: Write readable tests focused on behaviors and invariants, respect architecture rules and the verification policy, and check the impact of changes on dependencies and discoverability.

**Decision rule**: When multiple functionally equivalent implementations exist, prefer the one that minimizes the cost of understanding, locating, and modifying code in the future. Do not enforce arbitrary numeric thresholds, and do not treat CodeScene or static metrics as an architectural authority—metrics are signals, not autonomous goals.