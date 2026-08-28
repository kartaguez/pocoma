package com.kartaguez.pocoma.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

class HexagonalArchitectureTest {

	private static final String ROOT_PACKAGE = "com.kartaguez.pocoma";
	private static final String DOMAIN_PACKAGE = ROOT_PACKAGE + ".domain..";
	private static final String POT_DOMAIN_PACKAGE = ROOT_PACKAGE + ".domain.pot..";
	private static final String POT_POLICY_PACKAGE = ROOT_PACKAGE + ".domain.pot.policy..";
	private static final String BALANCE_PROJECTION_DOMAIN_PACKAGE = ROOT_PACKAGE
			+ ".domain.projection.balance..";
	private static final String ENGINE_PACKAGE = ROOT_PACKAGE + ".engine..";
	private static final String INFRA_PERSISTENCE_PACKAGE = ROOT_PACKAGE + ".infra.persistence.jpa..";
	private static final String SUPRA_PACKAGE = ROOT_PACKAGE + ".supra..";

	private static final String JPA_MATERIALIZABLE_EVENT_SOURCE_ADAPTER = ROOT_PACKAGE
			+ ".infra.persistence.jpa.adapter.pipeline.JpaMaterializableEventSourceAdapter";
	private static final String JPA_PIPELINE_TASK_WORK_SOURCE_ADAPTER = ROOT_PACKAGE
			+ ".infra.persistence.jpa.adapter.pipeline.JpaPipelineTaskWorkSourceAdapter";

	private static final Set<String> ALLOWED_INFRA_TO_SUPRA_DEPENDENCIES = Set.of(
			JPA_MATERIALIZABLE_EVENT_SOURCE_ADAPTER + " -> "
					+ ROOT_PACKAGE + ".supra.worker.taskmaterialization.core.MaterializableEventSource",
			JPA_PIPELINE_TASK_WORK_SOURCE_ADAPTER + " -> "
					+ ROOT_PACKAGE + ".supra.worker.pipelinetask.core.PipelineTaskClaimCriteria",
			JPA_PIPELINE_TASK_WORK_SOURCE_ADAPTER + " -> "
					+ ROOT_PACKAGE + ".supra.worker.pipelinetask.core.PipelineTaskWorkSource");

	private static final JavaClasses CLASSES = new ClassFileImporter().importPackages(ROOT_PACKAGE);

	@Test
	void domainDoesNotDependOnOuterLayersOrFrameworks() {
		noClasses()
				.that().resideInAPackage(DOMAIN_PACKAGE)
				.should().dependOnClassesThat().resideInAnyPackage(
						ENGINE_PACKAGE,
						ROOT_PACKAGE + ".infra..",
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..",
						"org.springframework..",
						"jakarta.persistence..")
				.check(CLASSES);
	}

	@Test
	void potDomainIsSelfContainedAndUsesItsExplicitNamespace() {
		noClasses()
				.that().resideInAPackage(POT_DOMAIN_PACKAGE)
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".domain.policy..",
						ROOT_PACKAGE + ".domain.projection..",
						ROOT_PACKAGE + ".domain.pipeline..",
						ROOT_PACKAGE + ".domain.consumption..",
						ENGINE_PACKAGE,
						ROOT_PACKAGE + ".infra..",
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..",
						ROOT_PACKAGE + ".orchestrator..",
						"org.springframework..",
						"jakarta.persistence..",
						"com.fasterxml.jackson..",
						"io.nats..")
				.check(CLASSES);

		Set<String> legacyPotPackages = CLASSES.stream()
				.map(javaClass -> javaClass.getPackageName())
				.filter(packageName -> Set.of(
						ROOT_PACKAGE + ".domain.aggregate",
						ROOT_PACKAGE + ".domain.association",
						ROOT_PACKAGE + ".domain.created",
						ROOT_PACKAGE + ".domain.draft",
						ROOT_PACKAGE + ".domain.entity",
						ROOT_PACKAGE + ".domain.exception",
						ROOT_PACKAGE + ".domain.factory",
						ROOT_PACKAGE + ".domain.value").stream()
						.anyMatch(packageName::startsWith))
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), legacyPotPackages, "Pot types must live below domain.pot");
	}

	@Test
	void potPoliciesAndBalanceProjectionRemainPureDomainCode() {
		noClasses()
				.that().resideInAnyPackage(POT_POLICY_PACKAGE, BALANCE_PROJECTION_DOMAIN_PACKAGE)
				.should().dependOnClassesThat().resideInAnyPackage(
						ENGINE_PACKAGE,
						ROOT_PACKAGE + ".infra..",
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..",
						ROOT_PACKAGE + ".orchestrator..",
						"org.springframework..",
						"jakarta.persistence..",
						"com.fasterxml.jackson..",
						"io.nats..")
				.check(CLASSES);

		Set<String> obsoleteDomainTypes = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".domain.policy")
						|| javaClass.getPackageName().equals(ROOT_PACKAGE + ".domain.projection"))
				.map(javaClass -> javaClass.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), obsoleteDomainTypes,
				"Pot policies and balance projection types must use their explicit namespaces");

		Set<String> policyDependenciesOutsidePot = dependenciesOutside(
				POT_POLICY_PACKAGE.substring(0, POT_POLICY_PACKAGE.length() - 2),
				Set.of(ROOT_PACKAGE + ".domain.pot", ROOT_PACKAGE + ".domain.pot.policy"));
		assertEquals(Set.of(), policyDependenciesOutsidePot,
				"domain-pot-policy may depend only on domain-pot and the JDK");

		Set<String> balanceDependenciesOutsidePot = dependenciesOutside(
				BALANCE_PROJECTION_DOMAIN_PACKAGE.substring(0, BALANCE_PROJECTION_DOMAIN_PACKAGE.length() - 2),
				Set.of(ROOT_PACKAGE + ".domain.projection.balance", ROOT_PACKAGE + ".domain.pot"));
		assertEquals(Set.of(), balanceDependenciesOutsidePot,
				"domain-projection-balance may depend only on domain-pot and the JDK");
	}

	@Test
	void targetApplicationAndProcessingPackagesDoNotDependOnLegacyEngineTypes() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.port.in.command..",
						ROOT_PACKAGE + ".engine.service.command..",
						ROOT_PACKAGE + ".engine.port.in.query..",
						ROOT_PACKAGE + ".engine.service.query..",
						ROOT_PACKAGE + ".engine.port.in.taskcreation..",
						ROOT_PACKAGE + ".engine.service.taskcreation..",
						ROOT_PACKAGE + ".engine.port.in.taskexecution..",
						ROOT_PACKAGE + ".engine.service.taskexecution..",
						ROOT_PACKAGE + ".engine..processing.command..",
						ROOT_PACKAGE + ".engine..processing.event..",
						ROOT_PACKAGE + ".engine..processing.task..")
				.should().dependOnClassesThat().resideInAPackage(ROOT_PACKAGE + ".engine.legacy..")
				.check(CLASSES);
	}

	@Test
	void potBusinessEventsBelongToTheDomainWhileRecordingMetadataRemainsInTheEngine() {
		Set<String> engineEventTypes = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().equals(ROOT_PACKAGE + ".engine.event"))
				.map(javaClass -> javaClass.getSimpleName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of("EventTraceMetadata", "RecordedEvent"), engineEventTypes,
				"engine.event must contain only application recording metadata");

		Set<String> potEventTypes = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().equals(ROOT_PACKAGE + ".domain.pot.event"))
				.filter(javaClass -> !javaClass.getSimpleName().equals("package-info"))
				.map(javaClass -> javaClass.getSimpleName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(
				"BusinessEvent",
				"ExpenseCreatedEvent",
				"ExpenseDeletedEvent",
				"ExpenseDetailsUpdatedEvent",
				"ExpenseSharesUpdatedEvent",
				"PotCreatedEvent",
				"PotDeletedEvent",
				"PotDetailsUpdatedEvent",
				"PotShareholdersAddedEvent",
				"PotShareholdersDetailsUpdatedEvent",
				"PotShareholdersWeightsUpdatedEvent"), potEventTypes,
				"all typed Pot facts must live in domain.pot.event");
	}

	@Test
	void pipelineAndTaskDomainsExposeOnlyTheirMinimalJdkContracts() {
		Set<String> pipelineTypes = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().equals(ROOT_PACKAGE + ".domain.pipeline"))
				.map(javaClass -> javaClass.getSimpleName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of("PipelineDefinition", "PipelineId"), pipelineTypes,
				"domain-pipeline must contain only pipeline identity and version");

		Set<String> taskTypes = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().equals(ROOT_PACKAGE + ".domain.task"))
				.map(javaClass -> javaClass.getSimpleName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of("TaskPayload"), taskTypes,
				"domain-task must contain only the functional payload contract");

		Set<String> nonJdkDependencies = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().equals(ROOT_PACKAGE + ".domain.pipeline")
						|| javaClass.getPackageName().equals(ROOT_PACKAGE + ".domain.task"))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(dependency -> dependency.getTargetClass())
				.filter(target -> !target.getPackageName().startsWith("java."))
				.filter(target -> !target.getPackageName().equals(ROOT_PACKAGE + ".domain.pipeline"))
				.filter(target -> !target.getPackageName().equals(ROOT_PACKAGE + ".domain.task"))
				.map(target -> target.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), nonJdkDependencies,
				"domain-pipeline and domain-task must depend only on the JDK");
	}

	@Test
	void engineDoesNotDependOnOuterLayersOrFrameworks() {
		noClasses()
				.that().resideInAPackage(ENGINE_PACKAGE)
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".infra..",
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..",
						ROOT_PACKAGE + ".orchestrator..",
						"org.springframework..",
						"jakarta.persistence..")
				.check(CLASSES);
	}

	@Test
	void consumptionEngineDoesNotDependOnOuterLayers() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.context.consumption..",
						ROOT_PACKAGE + ".engine.port.in.consumption..",
						ROOT_PACKAGE + ".engine.port.out.consumption..",
						ROOT_PACKAGE + ".engine.service.consumption..",
						ROOT_PACKAGE + ".engine.service.transaction.consumption..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.context..",
						ROOT_PACKAGE + ".engine.port.in.command..",
						ROOT_PACKAGE + ".engine.port.in.query..",
						ROOT_PACKAGE + ".engine.port.in.taskcreation..",
						ROOT_PACKAGE + ".engine.port.in.taskexecution..",
						ROOT_PACKAGE + ".engine.processing..",
						ROOT_PACKAGE + ".infra..",
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..",
						ROOT_PACKAGE + ".orchestrator..",
						"org.springframework..",
						"jakarta.persistence..")
				.check(CLASSES);

		Set<String> forbiddenTypeNames = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".engine.port.in.consumption")
						|| javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".engine.port.out.consumption")
						|| javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".engine.service.consumption")
						|| javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".engine.service.transaction.consumption"))
				.map(javaClass -> javaClass.getSimpleName())
				.filter(name -> Set.of("Command", "Event", "Task", "Pot", "Pipeline").stream()
						.anyMatch(name::contains))
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), forbiddenTypeNames,
				"engine-consumption must remain agnostic of consumed work families");
	}

	@Test
	void consumptionDomainDoesNotDependOnApplicationOrOuterLayers() {
		noClasses()
				.that().resideInAPackage(ROOT_PACKAGE + ".domain.consumption..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ENGINE_PACKAGE,
						ROOT_PACKAGE + ".infra..",
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..",
						ROOT_PACKAGE + ".orchestrator..",
						"org.springframework..",
						"jakarta.persistence..")
				.check(CLASSES);

		Set<String> nonJdkDependencies = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".domain.consumption"))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(dependency -> dependency.getTargetClass())
				.filter(target -> !target.getPackageName().startsWith("java."))
				.filter(target -> !target.getPackageName().startsWith(ROOT_PACKAGE + ".domain.consumption"))
				.map(target -> target.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), nonJdkDependencies, "domain-consumption must depend only on the JDK");

		Set<String> forbiddenTypeNames = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".domain.consumption"))
				.map(javaClass -> javaClass.getSimpleName())
				.filter(name -> Set.of("Command", "Event", "Task", "Pot", "Pipeline").stream()
						.anyMatch(name::contains))
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), forbiddenTypeNames,
				"domain-consumption must remain agnostic of consumed work families");

		Set<String> packages = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".domain.consumption."))
				.map(javaClass -> javaClass.getPackageName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(
				ROOT_PACKAGE + ".domain.consumption.claim",
				ROOT_PACKAGE + ".domain.consumption.key",
				ROOT_PACKAGE + ".domain.consumption.lifecycle"), packages,
				"domain-consumption must contain only claim, key and lifecycle families");
	}

	@Test
	void functionalCommandUseCasesDoNotDependOnDurableConsumption() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.port.in.command..",
						ROOT_PACKAGE + ".engine.service.command..",
						ROOT_PACKAGE + ".engine.service.transaction.command..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".domain.consumption..",
						ROOT_PACKAGE + ".engine.port.in.consumption..",
						ROOT_PACKAGE + ".engine.port.out.consumption..",
						ROOT_PACKAGE + ".engine.service.consumption..")
				.check(CLASSES);

		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.context..",
						ROOT_PACKAGE + ".engine.port.in.command..",
						ROOT_PACKAGE + ".engine.port.out.persistence..",
						ROOT_PACKAGE + ".engine.service.command..",
						ROOT_PACKAGE + ".engine.service.transaction.command..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine..processing.command..",
						ROOT_PACKAGE + ".domain.consumption..",
						ROOT_PACKAGE + ".engine.port.in.consumption..",
						ROOT_PACKAGE + ".engine.port.out.consumption..",
						ROOT_PACKAGE + ".engine.service.consumption..")
				.check(CLASSES);

		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.port.in.command..",
						ROOT_PACKAGE + ".engine.service.command..",
						ROOT_PACKAGE + ".engine.service.transaction.command..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.port.in.taskcreation..",
						ROOT_PACKAGE + ".engine.service.taskcreation..",
						ROOT_PACKAGE + ".engine.port.in.taskexecution..",
						ROOT_PACKAGE + ".engine.service.taskexecution..",
						ROOT_PACKAGE + ".engine..processing.event..",
						ROOT_PACKAGE + ".engine..processing.task..")
				.check(CLASSES);
	}

	@Test
	void commandProcessingDependsOnlyOnCommandAndGenericProcessingContracts() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine..processing.command..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.port.in.query..",
						ROOT_PACKAGE + ".engine.service.query..",
						ROOT_PACKAGE + ".engine.port.in.taskcreation..",
						ROOT_PACKAGE + ".engine.service.taskcreation..",
						ROOT_PACKAGE + ".engine.port.in.taskexecution..",
						ROOT_PACKAGE + ".engine.service.taskexecution..",
						ROOT_PACKAGE + ".engine.taskmaterialization..",
						ROOT_PACKAGE + ".infra..",
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..",
						ROOT_PACKAGE + ".orchestrator..",
						"org.springframework..",
						"jakarta.persistence..",
						"com.fasterxml.jackson..",
						"io.nats..")
				.check(CLASSES);
	}

	@Test
	void eventProcessingDependsOnlyOnEventsPipelinesAndGenericConsumption() {
		noClasses()
				.that().resideInAnyPackage(ROOT_PACKAGE + ".engine..processing.event..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine..processing.command..",
						ROOT_PACKAGE + ".engine..processing.task..",
						ROOT_PACKAGE + ".engine.port.in.command..",
						ROOT_PACKAGE + ".engine.port.in.query..",
						ROOT_PACKAGE + ".engine.port.in.taskcreation..",
						ROOT_PACKAGE + ".engine.service.taskcreation..",
						ROOT_PACKAGE + ".engine.port.in.taskexecution..",
						ROOT_PACKAGE + ".engine.service.taskexecution..",
						ROOT_PACKAGE + ".engine.taskmaterialization..",
						ROOT_PACKAGE + ".infra..",
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..",
						ROOT_PACKAGE + ".orchestrator..",
						"org.springframework..",
						"jakarta.persistence..",
						"com.fasterxml.jackson..",
						"io.nats..")
				.check(CLASSES);

		Set<String> recordedEventFields = CLASSES.get(ROOT_PACKAGE + ".engine.event.RecordedEvent")
				.getAllFields().stream()
				.map(field -> field.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of("eventId", "event", "recordedAt", "traceMetadata"), recordedEventFields,
				"RecordedEvent must not carry pipeline consumption state");
	}

	@Test
	void taskProcessingDependsOnlyOnPipelinesAndGenericConsumption() {
		noClasses()
				.that().resideInAnyPackage(ROOT_PACKAGE + ".engine..processing.task..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine..processing.command..",
						ROOT_PACKAGE + ".engine..processing.event..",
						ROOT_PACKAGE + ".engine.port.in.command..",
						ROOT_PACKAGE + ".engine.port.in.query..",
						ROOT_PACKAGE + ".engine.port.in.taskcreation..",
						ROOT_PACKAGE + ".engine.service.taskcreation..",
						ROOT_PACKAGE + ".engine.port.in.taskexecution..",
						ROOT_PACKAGE + ".engine.service.taskexecution..",
						ROOT_PACKAGE + ".engine.taskmaterialization..",
						ROOT_PACKAGE + ".infra..",
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..",
						ROOT_PACKAGE + ".orchestrator..",
						"org.springframework..",
						"jakarta.persistence..",
						"com.fasterxml.jackson..",
						"io.nats..")
				.check(CLASSES);

		Set<String> recordedTaskFields = CLASSES
				.get(ROOT_PACKAGE + ".engine.port.out.processing.task.model.RecordedTask")
				.getAllFields().stream()
				.map(field -> field.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(
				"taskId", "pipeline", "potId", "targetVersion", "createdAt",
				"taskType", "serializedPayload", "traceId"), recordedTaskFields,
				"RecordedTask must not carry claim or durable processing state");
	}

	@Test
	void recordedProcessingModelsDoNotCarryClaimOrLeaseState() {
		assertEquals(Set.of("commandId", "potId", "createdAt", "userContext", "commandIntent"),
				fieldNames(ROOT_PACKAGE + ".engine.port.out.processing.command.model.RecordedCommand"));
		assertEquals(Set.of("eventId", "event", "recordedAt", "traceMetadata"),
				fieldNames(ROOT_PACKAGE + ".engine.event.RecordedEvent"));
		assertEquals(Set.of(
				"taskId", "pipeline", "potId", "targetVersion", "createdAt",
				"taskType", "serializedPayload", "traceId"),
				fieldNames(ROOT_PACKAGE + ".engine.port.out.processing.task.model.RecordedTask"));
	}

	@Test
	void queryEngineIsIndependentFromProcessingAndFrameworks() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.port.in.query..",
						ROOT_PACKAGE + ".engine.port.out.query..",
						ROOT_PACKAGE + ".engine.service.query..",
						ROOT_PACKAGE + ".engine.service.transaction.query..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".domain.consumption..",
						ROOT_PACKAGE + ".engine.context.consumption..",
						ROOT_PACKAGE + ".engine.port.in.consumption..",
						ROOT_PACKAGE + ".engine.port.in.command..",
						ROOT_PACKAGE + ".engine.port.in.taskcreation..",
						ROOT_PACKAGE + ".engine.port.in.taskexecution..",
						ROOT_PACKAGE + ".engine.taskexecution..",
						ROOT_PACKAGE + ".engine.taskmaterialization..",
						ROOT_PACKAGE + ".supra.worker..",
						"org.springframework..",
						"jakarta.persistence..",
						"com.fasterxml.jackson..",
						"io.nats..")
				.check(CLASSES);
	}

	@Test
	void functionalUseCaseFamiliesDoNotDependOnConsumptionDomain() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.port.in.command..",
						ROOT_PACKAGE + ".engine.service.command..",
						ROOT_PACKAGE + ".engine.service.transaction.command..",
						ROOT_PACKAGE + ".engine.port.in.query..",
						ROOT_PACKAGE + ".engine.port.out.query..",
						ROOT_PACKAGE + ".engine.service.query..",
						ROOT_PACKAGE + ".engine.service.transaction.query..",
						ROOT_PACKAGE + ".engine.port.in.taskcreation..",
						ROOT_PACKAGE + ".engine.port.out.taskcreation..",
						ROOT_PACKAGE + ".engine.service.taskcreation..",
						ROOT_PACKAGE + ".engine.service.transaction.taskcreation..",
						ROOT_PACKAGE + ".engine.port.in.taskexecution..",
						ROOT_PACKAGE + ".engine.service.taskexecution..")
				.should().dependOnClassesThat().resideInAPackage(ROOT_PACKAGE + ".domain.consumption..")
				.check(CLASSES);
	}

	@Test
	void taskCreationEngineUsesTypedEventsWithoutTechnicalProcessingDependencies() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.port.in.taskcreation..",
						ROOT_PACKAGE + ".engine.port.out.taskcreation..",
						ROOT_PACKAGE + ".engine.service.taskcreation..",
						ROOT_PACKAGE + ".engine.service.transaction.taskcreation..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine..processing.event..",
						ROOT_PACKAGE + ".domain.consumption..",
						ROOT_PACKAGE + ".engine.context.consumption..",
						ROOT_PACKAGE + ".engine.port.in.consumption..",
						ROOT_PACKAGE + ".engine.port.out.consumption..",
						ROOT_PACKAGE + ".engine.service.consumption..",
						ROOT_PACKAGE + ".engine.model..",
						ROOT_PACKAGE + ".engine.taskmaterialization..",
						ROOT_PACKAGE + ".supra.worker..",
						ROOT_PACKAGE + ".orchestrator..",
						"org.springframework..",
						"jakarta.persistence..",
						"com.fasterxml.jackson..",
						"io.nats..")
				.check(CLASSES);
	}

	@Test
	void functionalBalanceProjectionDoesNotDependOnWorkersOrConsumption() {
		noClasses()
				.that().resideInAPackage(ROOT_PACKAGE + ".engine.service.projection")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".domain.consumption..",
						ROOT_PACKAGE + ".engine.port.in.consumption..",
						ROOT_PACKAGE + ".engine.service.consumption..",
						ROOT_PACKAGE + ".engine..processing..",
						ROOT_PACKAGE + ".supra.worker..",
						ROOT_PACKAGE + ".orchestrator..")
				.check(CLASSES);
	}

	@Test
	void typedTaskExecutionDoesNotDependOnDurableProcessingOrFrameworks() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.port.in.taskexecution..",
						ROOT_PACKAGE + ".engine.service.taskexecution..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine..processing.task..",
						ROOT_PACKAGE + ".domain.consumption..",
						ROOT_PACKAGE + ".engine.context.consumption..",
						ROOT_PACKAGE + ".engine.port.in.consumption..",
						ROOT_PACKAGE + ".engine.port.out.consumption..",
						ROOT_PACKAGE + ".engine.service.consumption..",
						ROOT_PACKAGE + ".engine.taskexecution..",
						ROOT_PACKAGE + ".supra.worker..",
						ROOT_PACKAGE + ".orchestrator..",
						"org.springframework..",
						"jakarta.persistence..",
						"com.fasterxml.jackson..",
						"io.nats..")
				.check(CLASSES);

		Set<String> forbiddenDurableTypes = Set.of(
				ROOT_PACKAGE + ".engine.taskexecution.model.LegacyPipelineTask",
				ROOT_PACKAGE + ".engine.taskexecution.model.ConfiguredTaskExecutionBinding",
				ROOT_PACKAGE + ".infra.persistence.jpa.entity.pipeline.JpaPipelineTaskStatus");
		Set<String> actualDependencies = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".engine.port.in.taskexecution")
						|| javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".engine.service.taskexecution"))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(dependency -> dependency.getTargetClass().getName())
				.filter(forbiddenDurableTypes::contains)
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), actualDependencies,
				"Typed task execution must not expose durable task or claim state");
	}

	@Test
	void httpControllersDoNotDependOnJpa() {
		noClasses()
				.that().resideInAPackage(ROOT_PACKAGE + ".supra.http..controller..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".infra.persistence.jpa..",
						"jakarta.persistence..")
				.check(CLASSES);
	}

	@Test
	void applicationUseCasesDoNotDependOnWorkersOrClaims() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine..port.in..usecase..",
						ROOT_PACKAGE + ".engine..service..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".supra.worker..",
						ROOT_PACKAGE + ".orchestrator.claimable..")
				.check(CLASSES);
	}

	@Test
	void infraToSupraDependenciesAreLimitedToTheKnownMigrationAllowList() {
		Set<String> actualDependencies = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".infra.persistence.jpa"))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.filter(dependency -> dependency.getTargetClass().getPackageName().startsWith(ROOT_PACKAGE + ".supra"))
				.map(HexagonalArchitectureTest::dependencyKey)
				.collect(Collectors.toUnmodifiableSet());

		assertEquals(ALLOWED_INFRA_TO_SUPRA_DEPENDENCIES, actualDependencies,
				"Any infra-to-supra dependency must be explicitly allow-listed for migration");
	}

	private static String dependencyKey(Dependency dependency) {
		return dependency.getOriginClass().getName() + " -> " + dependency.getTargetClass().getName();
	}

	private static Set<String> fieldNames(String className) {
		return CLASSES.get(className).getAllFields().stream()
				.map(field -> field.getName())
				.collect(Collectors.toUnmodifiableSet());
	}

	private static Set<String> dependenciesOutside(String sourcePackage, Set<String> allowedPackages) {
		return CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(sourcePackage))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(dependency -> dependency.getTargetClass())
				.filter(target -> !target.getPackageName().startsWith("java."))
				.filter(target -> allowedPackages.stream()
						.noneMatch(allowed -> target.getPackageName().equals(allowed)
								|| target.getPackageName().startsWith(allowed + ".")))
				.map(target -> target.getName())
				.collect(Collectors.toUnmodifiableSet());
	}
}
