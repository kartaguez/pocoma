package com.kartaguez.pocoma.domain.consumption.key;

import java.util.List;

/** Opaque, structurally comparable identity of one logical consumption. */
public record ConsumptionKey(ConsumableIdentity consumable, ConsumerIdentity consumer) {

	public ConsumptionKey {
		java.util.Objects.requireNonNull(consumable, "consumable must not be null");
		java.util.Objects.requireNonNull(consumer, "consumer must not be null");
	}

	/** Compatibility constructor for callers awaiting migration to explicit identities. */
	@Deprecated(forRemoval = true)
	public ConsumptionKey(String namespace, List<String> components) {
		this(legacyConsumable(namespace, components), legacyConsumer(namespace, components));
	}

	@Deprecated(forRemoval = true)
	public String namespace() {
		return switch (consumable.type()) {
			case "COMMAND" -> "command";
			case "EVENT" -> "event";
			case "TASK" -> "task";
			default -> consumable.components().getFirst();
		};
	}

	@Deprecated(forRemoval = true)
	public List<String> components() {
		return switch (consumable.type()) {
			case "COMMAND", "TASK" -> consumable.components();
			case "EVENT" -> List.of(
					consumer.components().get(0), consumer.components().get(1), consumable.components().getFirst());
			default -> consumable.components().subList(1, consumable.components().size());
		};
	}

	private static ConsumableIdentity legacyConsumable(String namespace, List<String> components) {
		List<String> values = validatedComponents(components);
		return switch (validatedNamespace(namespace)) {
			case "command" -> new ConsumableIdentity("COMMAND", values);
			case "event" -> {
				requireSize(values, 3, "event");
				yield new ConsumableIdentity("EVENT", List.of(values.get(2)));
			}
			case "task" -> new ConsumableIdentity("TASK", values);
			default -> {
				List<String> identity = new java.util.ArrayList<>(values.size() + 1);
				identity.add(namespace);
				identity.addAll(values);
				yield new ConsumableIdentity("LEGACY_CONSUMABLE", identity);
			}
		};
	}

	private static ConsumerIdentity legacyConsumer(String namespace, List<String> components) {
		List<String> values = validatedComponents(components);
		return switch (validatedNamespace(namespace)) {
			case "command" -> new ConsumerIdentity("COMMAND_PROCESSOR", List.of());
			case "event" -> {
				requireSize(values, 3, "event");
				yield new ConsumerIdentity("PIPELINE", values.subList(0, 2));
			}
			case "task" -> new ConsumerIdentity("TASK_EXECUTOR", List.of());
			default -> new ConsumerIdentity("LEGACY_CONSUMER", List.of());
		};
	}

	private static String validatedNamespace(String namespace) {
		if (namespace == null) {
			throw new NullPointerException("namespace must not be null");
		}
		if (namespace.isBlank()) {
			throw new IllegalArgumentException("namespace must not be blank");
		}
		return namespace;
	}

	private static List<String> validatedComponents(List<String> components) {
		return new ConsumableIdentity("VALIDATION", components).components();
	}

	private static void requireSize(List<String> values, int size, String namespace) {
		if (values.size() != size) {
			throw new IllegalArgumentException(namespace + " consumption keys require " + size + " components");
		}
	}
}
