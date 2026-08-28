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
						ROOT_PACKAGE + ".infra..",
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..",
						ROOT_PACKAGE + ".orchestrator..",
						"org.springframework..",
						"jakarta.persistence..")
				.check(CLASSES);
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
						ROOT_PACKAGE + ".domain.consumption..",
						ROOT_PACKAGE + ".engine.context.consumption..",
						ROOT_PACKAGE + ".engine.port.in.consumption..",
						ROOT_PACKAGE + ".engine.port.out.consumption..",
						ROOT_PACKAGE + ".engine.service.consumption..",
						ROOT_PACKAGE + ".engine.model..",
						ROOT_PACKAGE + ".supra.worker..",
						ROOT_PACKAGE + ".orchestrator..",
						"org.springframework..",
						"jakarta.persistence..",
						"com.fasterxml.jackson..",
						"io.nats..")
				.check(CLASSES);
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
}
