package com.kartaguez.pocoma.architecture;

import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

class HexagonalArchitectureTest {

	private static final String ROOT_PACKAGE = "com.kartaguez.pocoma";
	private static final String DOMAIN_PACKAGE = ROOT_PACKAGE + ".domain..";
	private static final String AUTHORIZATION_DOMAIN_PACKAGE = ROOT_PACKAGE + ".domain.authorization..";
	private static final String USER_IDENTITY_DOMAIN_PACKAGE = ROOT_PACKAGE + ".domain.useridentity";
	private static final String AUTHENTICATION_CONTRACT_PACKAGE = ROOT_PACKAGE + ".contracts.authentication";
	private static final String POT_DOMAIN_PACKAGE = ROOT_PACKAGE + ".domain.pot..";
	private static final String POT_PROJECTION_DOMAIN_PACKAGE = ROOT_PACKAGE + ".domain.projection.pot.definition";
	private static final String POT_POLICY_PACKAGE = ROOT_PACKAGE + ".domain.pot.policy..";
	private static final String POT_AUTHORIZATION_PACKAGE = ROOT_PACKAGE + ".domain.pot.authorization..";
	private static final String BALANCE_PROJECTION_DOMAIN_PACKAGE = ROOT_PACKAGE
			+ ".domain.projection.balance..";
	private static final String PROJECTION_DOMAIN_PACKAGE = ROOT_PACKAGE + ".domain.projection..";
	private static final String READ_PROJECTION_ENGINE_PACKAGE = ROOT_PACKAGE + ".engine.read.projection..";
	private static final String PROJECTION_CONTRACTS_ENGINE_PACKAGE = ROOT_PACKAGE
			+ ".port.projection..";
	private static final String PROJECTION_READ_PORT_PACKAGE = ROOT_PACKAGE
			+ ".engine.read.projection.port";
	private static final String PROJECTION_READ_SERVICE_PACKAGE = ROOT_PACKAGE
			+ ".engine.read.projection.service";
	private static final String PROJECTION_READ_EXCEPTION_PACKAGE = ROOT_PACKAGE
			+ ".engine.read.projection.exception";
	private static final String POT_READ_ENGINE_PACKAGE = ROOT_PACKAGE + ".engine.read.pot";
	private static final String ENGINE_PACKAGE = ROOT_PACKAGE + ".engine..";
	private static final String INFRA_PERSISTENCE_PACKAGE = ROOT_PACKAGE + ".infra.persistence.primary.jpa..";
	private static final String INFRA_READ_PERSISTENCE_PACKAGE = ROOT_PACKAGE + ".infra.persistence.read.jdbc..";
	private static final String INFRA_PROJECTION_PERSISTENCE_PACKAGE = ROOT_PACKAGE
			+ ".infra.persistence.projection.jdbc..";
	private static final String SUPRA_PACKAGE = ROOT_PACKAGE + ".supra..";

	private static final JavaClasses CLASSES = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages(ROOT_PACKAGE);

	@Test
	void consumptionDiscoveryDoesNotDecodeBusinessPayloads() {
		noClasses()
				.that().haveSimpleNameContaining("ProjectionMaterializationDiscovery")
				.should().dependOnClassesThat().resideInAnyPackage(
						"com.fasterxml.jackson..",
						ROOT_PACKAGE + ".domain.pot.event..",
						ROOT_PACKAGE + ".domain.pipeline..",
						ROOT_PACKAGE + ".engine.event..",
						ROOT_PACKAGE + ".infra.persistence.primary.jpa.adapter.outbox..")
				.check(CLASSES);

		noClasses()
				.that().haveSimpleNameContaining("CommandConsumptionDiscovery")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.pot..",
						ROOT_PACKAGE + ".engine..processing.command..",
						ROOT_PACKAGE + ".domain.pot..")
				.check(CLASSES);
	}

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
	void potAuthorizationKernelRemainsPureAndProviderNeutral() {
		noClasses()
				.that().resideInAPackage(POT_AUTHORIZATION_PACKAGE)
				.should().dependOnClassesThat().resideInAnyPackage(
						ENGINE_PACKAGE,
						ROOT_PACKAGE + ".infra..",
						ROOT_PACKAGE + ".supra..",
						ROOT_PACKAGE + ".runtime..",
						ROOT_PACKAGE + ".orchestrator..",
						ROOT_PACKAGE + ".pipeline..",
						"org.springframework..",
						"jakarta.persistence..",
						"com.fasterxml.jackson..")
				.check(CLASSES);
	}

	@Test
	void potDomainIsSelfContainedAndUsesItsExplicitNamespace() {
		noClasses()
				.that().resideInAPackage(POT_DOMAIN_PACKAGE)
				.and().resideOutsideOfPackage(POT_PROJECTION_DOMAIN_PACKAGE)
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

		Set<String> potVersionMetadataOwners = CLASSES.stream()
				.filter(javaClass -> javaClass.getSimpleName().equals("PotVersionMetadata"))
				.map(javaClass -> javaClass.getPackageName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(ROOT_PACKAGE + ".domain.pot.version"), potVersionMetadataOwners,
				"PotVersionMetadata is a canonical Pot temporal concept owned only by domain-pot");
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
				.filter(javaClass -> javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".domain.policy"))
				.map(javaClass -> javaClass.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), obsoleteDomainTypes,
				"Pot policies must use their explicit namespace; domain.projection owns generic contracts");

		Set<String> policyDependenciesOutsidePot = dependenciesOutside(
				POT_POLICY_PACKAGE.substring(0, POT_POLICY_PACKAGE.length() - 2),
				Set.of(ROOT_PACKAGE + ".domain.authorization", ROOT_PACKAGE + ".domain.pot",
						ROOT_PACKAGE + ".domain.pot.policy"));
		assertEquals(Set.of(), policyDependenciesOutsidePot,
				"domain-pot-policy may depend only on domain-authorization, domain-pot and the JDK");

		Set<String> balanceDependenciesOutsidePot = dependenciesOutside(
				BALANCE_PROJECTION_DOMAIN_PACKAGE.substring(0, BALANCE_PROJECTION_DOMAIN_PACKAGE.length() - 2),
				Set.of(ROOT_PACKAGE + ".domain.projection.balance", ROOT_PACKAGE + ".domain.pot"));
		assertEquals(Set.of(), balanceDependenciesOutsidePot,
				"domain-projection-balance may depend only on domain-pot and the JDK");
	}

	@Test
	void authorizationDomainIsGenericAndLegacyAuthorizationScopesAreGone() {
		Set<String> dependenciesOutsideAuthorization = dependenciesOutside(
				AUTHORIZATION_DOMAIN_PACKAGE.substring(0, AUTHORIZATION_DOMAIN_PACKAGE.length() - 2),
				Set.of(ROOT_PACKAGE + ".domain.authorization"));
		assertEquals(Set.of(), dependenciesOutsideAuthorization,
				"domain-authorization must depend only on the JDK");

		Set<String> legacyAuthorizationTypes = CLASSES.stream()
				.filter(javaClass -> javaClass.getName().equals(ROOT_PACKAGE + ".domain.pot.policy.scope.Scope")
						|| javaClass.getName().equals(ROOT_PACKAGE + ".engine.consume.command.model.Permission"))
				.map(javaClass -> javaClass.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), legacyAuthorizationTypes,
				"authorization must use only domain.authorization.Permission");
	}

	@Test
	void targetApplicationAndProcessingPackagesDoNotDependOnLegacyEngineTypes() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.consume.command.pot..",
						ROOT_PACKAGE + ".engine.consume.command.pot.dispatch..",
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
	void potBusinessEventsAndRecordingMetadataBelongToTheDomain() {
		Set<String> engineEventTypes = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().equals(ROOT_PACKAGE + ".engine.event"))
				.map(javaClass -> javaClass.getSimpleName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), engineEventTypes, "engine.event must have no types after D.22");

		Set<String> potEventTypes = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().equals(ROOT_PACKAGE + ".domain.pot.event"))
				.filter(javaClass -> !javaClass.getSimpleName().equals("package-info"))
				.map(javaClass -> javaClass.getSimpleName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(
				"BusinessEvent",
				"EventTraceMetadata",
				"RecordedEvent",
				"ExpenseCreatedEvent",
				"ExpenseDeletedEvent",
				"ExpenseDetailsUpdatedEvent",
				"ExpenseSharesUpdatedEvent",
				"PotCreatedEvent",
				"PotDeletedEvent",
				"PotDetailsUpdatedEvent",
				"PotShareholdersAddedEvent",
				"PotShareholdersDetailsUpdatedEvent",
				"PotShareholdersWeightsUpdatedEvent",
				"PocomaEventTypes"), potEventTypes,
				"all typed Pot facts must live in domain.pot.event");
	}

	@Test
	void projectionCoreDependsOnlyOnTheJdkAndItself() {
		Set<String> dependenciesOutsideProjectionCore = CLASSES.stream()
				.filter(javaClass -> isProjectionCorePackage(javaClass.getPackageName()))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(Dependency::getTargetClass)
				.filter(target -> !target.getPackageName().startsWith("java."))
				.filter(target -> !isProjectionCorePackage(target.getPackageName()))
				.map(target -> target.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), dependenciesOutsideProjectionCore,
				"domain-projection core must depend only on the JDK and itself");
	}

	@Test
	void potProjectionContractsDependOnlyOnTheProjectionDomainAndTheJdk() {
		Set<String> dependenciesOutsidePotProjectionContracts = dependenciesOutside(
				POT_PROJECTION_DOMAIN_PACKAGE,
				Set.of(POT_PROJECTION_DOMAIN_PACKAGE, ROOT_PACKAGE + ".domain.projection"));
		assertEquals(Set.of(), dependenciesOutsidePotProjectionContracts,
				"domain-pot-projection must contain only pure shared projection definitions");
	}

	private static boolean isProjectionCorePackage(String packageName) {
		String projectionCorePackage = ROOT_PACKAGE + ".domain.projection";
		String projectionLegacyPackage = projectionCorePackage + ".legacy";
		String balanceProjectionPackage = projectionCorePackage + ".balance";
		return (packageName.equals(projectionCorePackage) || packageName.startsWith(projectionCorePackage + "."))
				&& !packageName.equals(projectionLegacyPackage)
				&& !packageName.startsWith(projectionLegacyPackage + ".")
				&& !packageName.equals(balanceProjectionPackage)
				&& !packageName.startsWith(balanceProjectionPackage + ".");
	}

	@Test
	void engineDoesNotDependOnOuterLayersOrFrameworks() {
		noClasses()
				.that().resideInAPackage(ENGINE_PACKAGE)
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".infra..",
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..",
						"org.springframework..",
						"jakarta.persistence..")
				.check(CLASSES);
		// TARGET gives the Task specialization one explicit engine -> generic orchestrator edge.
		noClasses().that().resideInAPackage(ENGINE_PACKAGE)
				.and().resideOutsideOfPackage(ROOT_PACKAGE + ".engine.consume.projectiontask..")
				.should().dependOnClassesThat().resideInAPackage(ROOT_PACKAGE + ".orchestrator..")
				.check(CLASSES);
	}

	@Test
	void foundationContractsAndPortsStayInwardOnly() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".authentication..",
						ROOT_PACKAGE + ".observability..",
						ROOT_PACKAGE + ".contracts.registration..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine..", ROOT_PACKAGE + ".infra..",
						ROOT_PACKAGE + ".runtime..", "org.springframework.security..",
						"jakarta.persistence..")
				.check(CLASSES);
		noClasses()
				.that().resideInAPackage(ROOT_PACKAGE + ".port..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine..", ROOT_PACKAGE + ".infra..",
						ROOT_PACKAGE + ".runtime..")
				.check(CLASSES);
	}

	@Test
	void genericConsumptionHasNoCapabilitySpecificDependencies() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".domain.consumption..",
						ROOT_PACKAGE + ".engine.port.in.consumption..",
						ROOT_PACKAGE + ".engine.port.out.consumption..",
						ROOT_PACKAGE + ".engine.service.consumption..",
						ROOT_PACKAGE + ".engine.service.transaction.consumption..",
						ROOT_PACKAGE + ".orchestrator.consumption..",
						ROOT_PACKAGE + ".orchestrator.poll.consumption..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".domain.event..", ROOT_PACKAGE + ".domain.pot..",
						ROOT_PACKAGE + ".domain.projection..", ROOT_PACKAGE + ".domain.useridentity..",
						ROOT_PACKAGE + ".engine.consume.command..", ROOT_PACKAGE + ".engine.registration..",
						ROOT_PACKAGE + ".engine.consume.projectiontask..", ROOT_PACKAGE + ".engine.read..")
				.check(CLASSES);
	}

	@Test
	void projectionContractsEngineDependsOnlyOnTheJdkAndProjectionDomain() {
		String contractsPackage = PROJECTION_CONTRACTS_ENGINE_PACKAGE.substring(0,
				PROJECTION_CONTRACTS_ENGINE_PACKAGE.length() - 2);
		Set<String> dependenciesOutsideProjectionContracts = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(contractsPackage))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(Dependency::getTargetClass)
				.filter(target -> !target.getPackageName().startsWith("java."))
				.filter(target -> !target.getPackageName().equals(contractsPackage)
						&& !target.getPackageName().startsWith(contractsPackage + "."))
				.filter(target -> !isProjectionCorePackage(target.getPackageName()))
				.map(target -> target.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), dependenciesOutsideProjectionContracts,
				"engine-projection-contracts must depend only on the JDK and domain-projection");
	}

	@Test
	void exactProjectionReadEngineDependsOnlyOnItsPureContracts() {
		String contractsPackage = PROJECTION_CONTRACTS_ENGINE_PACKAGE.substring(0,
				PROJECTION_CONTRACTS_ENGINE_PACKAGE.length() - 2);
		Set<String> dependenciesOutsideProjectionRead = CLASSES.stream()
				.filter(javaClass -> isProjectionReadEnginePackage(javaClass.getPackageName()))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(Dependency::getTargetClass)
				.filter(target -> !target.getPackageName().startsWith("java."))
				.filter(target -> !isProjectionReadEnginePackage(target.getPackageName()))
				.filter(target -> !isProjectionCorePackage(target.getPackageName()))
				.filter(target -> !target.getPackageName().startsWith(contractsPackage))
				.map(target -> target.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), dependenciesOutsideProjectionRead,
				"engine-projection-read must depend only on the JDK, domain-projection "
						+ "and engine-projection-contracts");
	}

	@Test
	void potReadEngineDependsOnlyOnItsPureApplicationAndDomainContracts() {
		Set<String> dependenciesOutsidePotRead = dependenciesOutside(
				POT_READ_ENGINE_PACKAGE,
				Set.of(
						POT_READ_ENGINE_PACKAGE,
						ROOT_PACKAGE + ".domain.authorization",
						ROOT_PACKAGE + ".domain.pot",
						ROOT_PACKAGE + ".domain.pot.authorization",
						POT_PROJECTION_DOMAIN_PACKAGE,
						ROOT_PACKAGE + ".domain.projection",
						PROJECTION_READ_PORT_PACKAGE,
                        USER_IDENTITY_DOMAIN_PACKAGE, ROOT_PACKAGE + ".engine.read.currentbinding"));
		assertEquals(Set.of(), dependenciesOutsidePotRead,
				"engine-read-pot must depend only on the JDK, authorization and Pot domains, "
						+ "domain-projection and engine-projection-read");
	}

	@Test
	void exactReadPotBoundaryKnowsNoLegacySelectionOrDeliverySubsystem() {
		noClasses()
				.that().resideInAPackage(POT_READ_ENGINE_PACKAGE + "..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".domain.pipeline..",
						ROOT_PACKAGE + ".domain.projection.legacy..",
						ROOT_PACKAGE + ".engine.pipeline..",
						ROOT_PACKAGE + ".infra.persistence.read.jdbc..",
						ROOT_PACKAGE + ".supra..",
						ROOT_PACKAGE + ".runtime..")
				.check(CLASSES);

		noClasses()
				.that().resideInAnyPackage(
						PROJECTION_READ_PORT_PACKAGE + "..",
						PROJECTION_READ_SERVICE_PACKAGE + "..",
						PROJECTION_READ_EXCEPTION_PACKAGE + "..",
						INFRA_PROJECTION_PERSISTENCE_PACKAGE)
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".domain.authorization..",
						ROOT_PACKAGE + ".domain.pipeline..",
						ROOT_PACKAGE + ".domain.projection.legacy..",
						ROOT_PACKAGE + ".engine.pipeline..",
						ROOT_PACKAGE + ".infra.persistence.read.jdbc..",
						ROOT_PACKAGE + ".supra..",
						ROOT_PACKAGE + ".runtime..")
				.check(CLASSES);
	}

	@Test
	void projectionTaskEngineRemainsPureAndKnowsNoLegacyExecutionConcept() {
		String taskPackage = ROOT_PACKAGE + ".engine.consume.projectiontask";
		Set<String> dependenciesOutsideProjectionTask = dependenciesOutside(
				taskPackage,
				Set.of(
						taskPackage,
						ROOT_PACKAGE + ".domain.projection",
						ROOT_PACKAGE + ".domain.pot",
						ROOT_PACKAGE + ".domain.consumption",
						ROOT_PACKAGE + ".engine.port.in.consumption",
						ROOT_PACKAGE + ".engine.port.out.consumption",
						ROOT_PACKAGE + ".port.projection",
						ROOT_PACKAGE + ".projector.pot",
						ROOT_PACKAGE + ".orchestrator.consumption"));
		assertEquals(Set.of(), dependenciesOutsideProjectionTask,
				"engine-consume-projection-task may use only generic Consumption orchestration");

		noClasses()
				.that().resideInAPackage(taskPackage + "..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".domain.projection.legacy..",
						ROOT_PACKAGE + ".domain.pipeline..",
						ROOT_PACKAGE + ".engine.port.in.taskexecution..",
						ROOT_PACKAGE + ".engine.service.taskexecution..")
				.check(CLASSES);
	}

	private static boolean isProjectionReadEnginePackage(String packageName) {
		return packageName.equals(PROJECTION_READ_PORT_PACKAGE)
				|| packageName.startsWith(PROJECTION_READ_PORT_PACKAGE + ".")
				|| packageName.equals(PROJECTION_READ_SERVICE_PACKAGE)
				|| packageName.startsWith(PROJECTION_READ_SERVICE_PACKAGE + ".")
				|| packageName.equals(PROJECTION_READ_EXCEPTION_PACKAGE)
				|| packageName.startsWith(PROJECTION_READ_EXCEPTION_PACKAGE + ".");
	}

	@Test
	void commandAdmissionConsumesOnlyTheProviderNeutralAuthenticatedPrincipal() {
		noClasses()
				.that().resideInAPackage(ROOT_PACKAGE + ".engine.admit.command..")
				.should().dependOnClassesThat().resideInAnyPackage(
						"org.springframework..",
						"org.springframework.security..",
						"org.keycloak..",
						ROOT_PACKAGE + ".supra..",
						ROOT_PACKAGE + ".infra..",
						ROOT_PACKAGE + ".locator..")
				.check(CLASSES);

		Set<String> providerSpecificTypes = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(
						ROOT_PACKAGE + ".engine.admit.command"))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(Dependency::getTargetClass)
				.map(javaClass -> javaClass.getName())
				.filter(name -> name.contains("Jwt") || name.contains("Keycloak")
						|| name.contains("GrantedAuthority") || name.contains("SecurityContext"))
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), providerSpecificTypes,
				"command admission must receive only AuthenticatedExternalPrincipal");

		assertEquals(Set.of("commandType", "bindingId", "serializedPayload", "principal"),
				fieldNames(ROOT_PACKAGE
						+ ".engine.admit.command.model.SubmitRecordedCommandInput"),
				"admission input must carry the client BindingId and provider-neutral principal");
		Set<String> serviceDependencies = directDependencyNames(
				ROOT_PACKAGE + ".engine.admit.command.SubmitRecordedCommandService");
		assertTrue(serviceDependencies.contains(
				ROOT_PACKAGE + ".contracts.command.TargetCommandEnvelope"));
		assertFalse(serviceDependencies.stream().anyMatch(name -> name.endsWith("AuthorizationSnapshot")
				|| name.endsWith("Permission") || name.endsWith("ExternalIdentityResolverPort")
				|| name.endsWith("ExternalIdentityBindingPort")),
				"TARGET_V2 admission must neither resolve User/binding nor translate AuthZ");

		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".supra.http.write..",
						ROOT_PACKAGE + ".engine.admit.command..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".infra.persistence.primary.jpa.adapter.identity..",
						ROOT_PACKAGE + ".infra.persistence.primary.jpa.repository.identity..",
						ROOT_PACKAGE + ".engine.consume.command.pot.dispatch..",
						ROOT_PACKAGE + ".engine.read.pot..",
						ROOT_PACKAGE + ".engine.read..",
						ROOT_PACKAGE + ".infra.read..")
				.check(CLASSES);
	}

	@Test
	void userIdentityOwnsItsCanonicalTypesAndRemainsFrameworkFree() {
		Map<String, String> canonicalOwners = Map.of(
				"User", USER_IDENTITY_DOMAIN_PACKAGE + ".User",
				"PocomaUserId", USER_IDENTITY_DOMAIN_PACKAGE + ".PocomaUserId",
				"ExternalIdentity", USER_IDENTITY_DOMAIN_PACKAGE + ".ExternalIdentity",
				"BindingId", USER_IDENTITY_DOMAIN_PACKAGE + ".BindingId",
				"BindingRevision", USER_IDENTITY_DOMAIN_PACKAGE + ".BindingRevision",
				"ExternalIdentityBindingFact", USER_IDENTITY_DOMAIN_PACKAGE + ".ExternalIdentityBindingFact",
				"ExternalIdentityAttached", USER_IDENTITY_DOMAIN_PACKAGE + ".ExternalIdentityAttached",
				"ExternalIdentityDetached", USER_IDENTITY_DOMAIN_PACKAGE + ".ExternalIdentityDetached");

		canonicalOwners.forEach((simpleName, owner) -> {
			Set<String> definitions = CLASSES.stream()
					.filter(javaClass -> javaClass.getSimpleName().equals(simpleName))
					.map(javaClass -> javaClass.getName())
					.collect(Collectors.toUnmodifiableSet());
			assertEquals(Set.of(owner), definitions, simpleName + " must have one canonical owner");
		});

		assertEquals(Set.of(), dependenciesOutside(
				USER_IDENTITY_DOMAIN_PACKAGE,
				Set.of(USER_IDENTITY_DOMAIN_PACKAGE)),
				"User/Identity must depend only on the JDK");

		noClasses()
				.that().resideInAPackage(USER_IDENTITY_DOMAIN_PACKAGE + "..")
				.should().dependOnClassesThat().resideInAnyPackage(
						"org.springframework..",
						"org.springframework.security..",
						"org.keycloak..",
						"jakarta.persistence..")
				.check(CLASSES);
	}

	@Test
	void bindingLifecycleWriterIsAtomicWhileReadProjectionRemainsOutsideWriteAuthorityAndCommandRuntime() {
		Set<String> authorityDependencies = directDependencyNames(
				ROOT_PACKAGE + ".infra.persistence.primary.jpa.adapter.identity.JpaExternalIdentityBindingAdapter");
		assertTrue(authorityDependencies.stream().anyMatch(name -> name.contains("BindingFact")));
		assertTrue(authorityDependencies.stream().anyMatch(name -> name.contains("BindingStream")));

		Set<String> lifecycleToAuthorityDependencies = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(
						ROOT_PACKAGE + ".infra.persistence.primary.jpa"))
				.filter(javaClass -> javaClass.getSimpleName().contains("BindingFact")
						|| javaClass.getSimpleName().contains("BindingStream"))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(Dependency::getTargetClass)
				.map(javaClass -> javaClass.getName())
				.filter(name -> name.endsWith("ExternalIdentityJdbcRepository")
						|| name.endsWith("JpaExternalIdentityBindingAdapter")
						|| name.endsWith("ExternalIdentityBindingPort"))
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), lifecycleToAuthorityDependencies,
				"the lifecycle journal and stream must not become a second binding authority");

		Set<String> commandRuntimeLifecycleDependencies = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".engine.consume.command")
						|| javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".runtime.command"))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(Dependency::getTargetClass)
				.map(javaClass -> javaClass.getName())
				.filter(name -> name.endsWith("ExternalIdentityBindingFactPort")
						|| name.endsWith("ExternalIdentityBindingStreamPort"))
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), commandRuntimeLifecycleDependencies,
				"Command processing must not consult binding lifecycle persistence");

		noClasses().that().resideInAnyPackage(
				ROOT_PACKAGE + ".engine.consume.command..", ROOT_PACKAGE + ".runtime.command..")
				.should().dependOnClassesThat().resideInAPackage(ROOT_PACKAGE + ".engine.read.currentbinding..")
				.check(CLASSES);
		noClasses().that().resideInAPackage(ROOT_PACKAGE + ".supra.consume.binding..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".infra.persistence.primary.jpa.adapter.identity..",
						ROOT_PACKAGE + ".infra.persistence.primary.jpa.repository.identity..")
				.check(CLASSES);
		noClasses().that().resideInAPackage(ROOT_PACKAGE + ".supra.http.write..")
				.should().dependOnClassesThat().resideInAPackage(ROOT_PACKAGE + ".engine.read.currentbinding..")
				.check(CLASSES);
	}

	@Test
	void httpLayersDoNotAccessTheUserIdentityPersistenceAuthorityDirectly() {
		noClasses()
				.that().resideInAPackage(ROOT_PACKAGE + ".supra..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".infra.persistence.primary.jpa.repository.identity..",
						ROOT_PACKAGE + ".infra.persistence.primary.jpa.adapter.identity..")
				.check(CLASSES);
	}

	@Test
	void authenticatedPrincipalBelongsToTheNeutralAuthenticationBoundary() {
		Set<String> definitions = CLASSES.stream()
				.filter(javaClass -> javaClass.getSimpleName().equals("AuthenticatedExternalPrincipal"))
				.map(javaClass -> javaClass.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(AUTHENTICATION_CONTRACT_PACKAGE + ".AuthenticatedExternalPrincipal"), definitions);

		assertEquals(Set.of(), dependenciesOutside(
				AUTHENTICATION_CONTRACT_PACKAGE,
				Set.of(AUTHENTICATION_CONTRACT_PACKAGE, USER_IDENTITY_DOMAIN_PACKAGE)),
				"authentication contracts may depend only on User/Identity and the JDK");

		Set<String> permissionDependencies = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(AUTHENTICATION_CONTRACT_PACKAGE))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(Dependency::getTargetClass)
				.map(javaClass -> javaClass.getName())
				.filter(name -> name.equals(ROOT_PACKAGE + ".domain.authorization.Permission"))
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), permissionDependencies,
				"external authenticated authorities must remain distinct from Pocoma Permission");
	}

	@Test
	void springSecurityAuthenticationRemainsInTheDedicatedSupra() {
		Set<String> springSecurityUsersOutsideSupra = CLASSES.stream()
				.filter(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
						.anyMatch(dependency -> dependency.getTargetClass().getPackageName()
								.startsWith("org.springframework.security")))
				.filter(javaClass -> !javaClass.getPackageName().startsWith(
						ROOT_PACKAGE + ".runtime.web.authentication"))
				.filter(javaClass -> !javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".runtime"))
				.map(javaClass -> javaClass.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), springSecurityUsersOutsideSupra,
				"only the Spring authentication supra and runtime composition may know Spring Security");

		noClasses()
				.that().resideInAPackage(ROOT_PACKAGE + ".supra.http.write..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.pot..",
						ROOT_PACKAGE + ".engine.read.commandresult..",
						ROOT_PACKAGE + ".engine.projection..",
						ROOT_PACKAGE + ".locator.consumption..",
						ROOT_PACKAGE + ".orchestrator.consumption..")
				.check(CLASSES);

		noClasses()
				.that().resideInAPackage(ROOT_PACKAGE + ".supra.http.read..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.consume.command.port.out..",
						ROOT_PACKAGE + ".engine.consume.command.pot.dispatch..",
						ROOT_PACKAGE + ".binding..",
						ROOT_PACKAGE + ".locator.consumption..",
						ROOT_PACKAGE + ".engine.admit.command.port.in..",
						ROOT_PACKAGE + ".orchestrator.consumption..")
				.check(CLASSES);
	}

	@Test
	void consumptionEngineDoesNotDependOnOuterLayers() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.write.pot.context.consumption..",
						ROOT_PACKAGE + ".engine.port.in.consumption..",
						ROOT_PACKAGE + ".engine.port.out.consumption..",
						ROOT_PACKAGE + ".engine.service.consumption..",
						ROOT_PACKAGE + ".engine.service.transaction.consumption..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.write.pot.context..",
						ROOT_PACKAGE + ".engine.consume.command.pot..",
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
	void targetConsumptionPersistenceDoesNotDependOnLegacyClaimTokens() {
		Set<String> targetPortNames = Set.of(
				"ConsumptionLifecyclePersistencePort",
				"ConsumptionQueryPort",
				"ConsumptionProvenancePersistencePort");
		Set<String> claimTokenDependencies = CLASSES.stream()
				.filter(javaClass -> targetPortNames.contains(javaClass.getSimpleName())
						|| javaClass.getPackageName().startsWith(
								ROOT_PACKAGE + ".infra.persistence.primary.jpa.adapter.consumption")
						|| javaClass.getPackageName().startsWith(
								ROOT_PACKAGE + ".infra.persistence.primary.jpa.entity.consumption")
						|| javaClass.getPackageName().startsWith(
								ROOT_PACKAGE + ".infra.persistence.primary.jpa.repository.consumption"))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(dependency -> dependency.getTargetClass())
				.filter(target -> target.getSimpleName().equals("ClaimToken"))
				.map(target -> target.getName())
				.collect(Collectors.toUnmodifiableSet());

		assertEquals(Set.of(), claimTokenDependencies,
				"target consumption persistence must fence exclusively with ClaimId");
	}

	@Test
	void transactionalConsumptionExecutionDoesNotDependOnLegacyExecutionGuard() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.port.in.consumption.contract..",
						ROOT_PACKAGE + ".engine.port.in.consumption.input..",
						ROOT_PACKAGE + ".engine.port.in.consumption.result..",
						ROOT_PACKAGE + ".engine.port.in.consumption.usecase..",
						ROOT_PACKAGE + ".engine.service.consumption..",
						ROOT_PACKAGE + ".engine.service.transaction.consumption..")
				.should().dependOnClassesThat().resideInAPackage(
						ROOT_PACKAGE + ".engine..execution..")
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
				ROOT_PACKAGE + ".domain.consumption.lifecycle",
				ROOT_PACKAGE + ".domain.consumption.provenance",
				ROOT_PACKAGE + ".domain.consumption.segmentation"), packages,
				"domain-consumption contains generic claim, key, lifecycle, provenance and segmentation");
	}

	@Test
	void functionalCommandUseCasesDoNotDependOnDurableConsumption() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.consume.command.pot..",
						ROOT_PACKAGE + ".engine.consume.command.pot.dispatch..",
						ROOT_PACKAGE + ".engine.service.transaction.command..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".domain.consumption.claim..",
						ROOT_PACKAGE + ".domain.consumption.key..",
						ROOT_PACKAGE + ".domain.consumption.provenance..",
						ROOT_PACKAGE + ".engine.port.in.consumption..",
						ROOT_PACKAGE + ".engine.port.out.consumption..",
						ROOT_PACKAGE + ".engine.service.consumption..")
				.check(CLASSES);

		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.write.pot.context..",
						ROOT_PACKAGE + ".engine.consume.command.pot..",
						ROOT_PACKAGE + ".engine.write.pot.port.persistence..",
						ROOT_PACKAGE + ".engine.consume.command.pot.dispatch..",
						ROOT_PACKAGE + ".engine.service.transaction.command..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine..processing.command..",
						ROOT_PACKAGE + ".domain.consumption.claim..",
						ROOT_PACKAGE + ".domain.consumption.key..",
						ROOT_PACKAGE + ".domain.consumption.provenance..",
						ROOT_PACKAGE + ".engine.port.in.consumption..",
						ROOT_PACKAGE + ".engine.port.out.consumption..",
						ROOT_PACKAGE + ".engine.service.consumption..")
				.check(CLASSES);

		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.consume.command.pot..",
						ROOT_PACKAGE + ".engine.consume.command.pot.dispatch..",
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
	void potCommandAdaptersStayOutsideTransactionsInfrastructureAndEventSerialization() {
		String commandServicePackage = ROOT_PACKAGE + ".engine.consume.command.pot.dispatch..";
		String decoderPackage = ROOT_PACKAGE + ".engine.consume.command.pot.decode..";

		noClasses()
				.that().resideInAPackage(commandServicePackage)
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.consume.command.port.out..",
						ROOT_PACKAGE + ".infra..",
						"org.springframework..",
						"jakarta.persistence..",
						"com.fasterxml.jackson..")
				.check(CLASSES);

		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.write.pot.context..",
						ROOT_PACKAGE + ".engine.consume.command.pot.dispatch..",
						ROOT_PACKAGE + ".engine.service.transaction.command..")
				.should().dependOnClassesThat().resideInAnyPackage("com.fasterxml.jackson..")
				.check(CLASSES);

		noClasses()
				.that().resideInAPackage(decoderPackage)
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.consume.command.port.out..",
						ROOT_PACKAGE + ".infra..",
						"org.springframework..",
						"jakarta.persistence..")
				.check(CLASSES);

		Set<String> adapterTransactionDependencies = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().equals(ROOT_PACKAGE + ".engine.consume.command.pot.dispatch"))
				.filter(javaClass -> javaClass.getSimpleName().endsWith("CommandUseCaseAdapter"))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(dependency -> dependency.getTargetClass().getName())
				.filter(name -> name.endsWith(".TransactionRunner") || name.contains(".service.transaction."))
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), adapterTransactionDependencies,
				"Pot Command adapters must join the caller transaction instead of creating one");

		Set<String> commandConsumptionDependencies = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".engine.consume.command.pot.dispatch"))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(dependency -> dependency.getTargetClass().getName())
				.filter(name -> name.startsWith(ROOT_PACKAGE + ".domain.consumption."))
				.filter(name -> !name.equals(ROOT_PACKAGE + ".domain.consumption.lifecycle.TerminalReason"))
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), commandConsumptionDependencies,
				"Pot Command services may use TerminalReason but no durable consumption model");
	}

	@Test
	void genericCommandEngineDependsOnlyOnGenericCommandAndTerminalReasonContracts() {
		String commandPackage = ROOT_PACKAGE + ".engine.consume.command";
		Set<String> allowedPackages = Set.of(commandPackage, ROOT_PACKAGE + ".domain.authorization",
				ROOT_PACKAGE + ".domain.consumption.lifecycle", ROOT_PACKAGE + ".domain.event",
				USER_IDENTITY_DOMAIN_PACKAGE, ROOT_PACKAGE + ".port.binding.authority",
				ROOT_PACKAGE + ".contracts.command");
		Set<String> dependenciesOutsideCommand = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(commandPackage))
				.filter(javaClass -> !javaClass.getPackageName().startsWith(commandPackage + ".pot"))
				.filter(javaClass -> !javaClass.getPackageName().startsWith(commandPackage + ".consumption"))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.map(dependency -> dependency.getTargetClass())
				.filter(target -> !target.getPackageName().startsWith("java."))
				.filter(target -> allowedPackages.stream().noneMatch(allowed ->
						target.getPackageName().equals(allowed)
								|| target.getPackageName().startsWith(allowed + ".")))
				.map(target -> target.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), dependenciesOutsideCommand,
				"the generic Command kernel must not import Pot dispatch or infrastructure");

		noClasses()
				.that().resideInAPackage(commandPackage + "..")
				.should().dependOnClassesThat().resideInAPackage(ROOT_PACKAGE + ".domain.consumption.key..")
				.check(CLASSES);

		Set<String> forbiddenDurableExecutionTypes = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(commandPackage))
				.map(javaClass -> javaClass.getSimpleName())
				.filter(name -> Set.of("Claim", "Lease", "Slot", "WorkerSegment").stream().anyMatch(name::contains))
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), forbiddenDurableExecutionTypes,
				"engine-command execution contracts must not expose claiming or fencing state");

		assertEquals(Set.of("commandId", "commandType", "serializedPayload", "submittedAt", "envelope"),
				fieldNames(ROOT_PACKAGE + ".contracts.command.RecordedCommand"),
				"RecordedCommand must contain durable request data and no consumption lifecycle");
	}

	@Test
	void targetCommandConsumptionKeepsIdentityResolutionInsideTheWriteWorkerBoundary() {
		assertEquals(Set.of("externalIdentity", "bindingId", "authenticationEvidence"),
				fieldNames(ROOT_PACKAGE + ".contracts.command.TargetCommandEnvelope"),
				"TARGET_V2 must contain E, B and AuthN evidence only");

		Set<String> executionDependencies = directDependencyNames(
				ROOT_PACKAGE + ".engine.consume.command.execution.ExecuteRecordedCommandService");
		assertTrue(executionDependencies.contains(
				ROOT_PACKAGE + ".port.binding.authority.ExternalIdentityBindingPort"));
		assertFalse(executionDependencies.contains(
				USER_IDENTITY_DOMAIN_PACKAGE + ".ExternalIdentityResolverPort"),
				"TARGET_V2 execution must never fall back to legacy E-to-U resolution");
		assertTrue(executionDependencies.stream().noneMatch(name -> name.contains(".read.")
				|| name.contains("Keycloak") || name.contains("Jwt")),
				"TARGET_V2 execution must depend on neither READ nor provider-specific authentication");

		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".supra.http.write..",
						ROOT_PACKAGE + ".engine.admit.command..")
				.should().dependOnClassesThat().haveFullyQualifiedName(
						ROOT_PACKAGE + ".port.binding.authority.ExternalIdentityBindingPort")
				.check(CLASSES);
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".supra.http.write..",
						ROOT_PACKAGE + ".engine.admit.command..")
				.should().dependOnClassesThat().haveFullyQualifiedName(
						USER_IDENTITY_DOMAIN_PACKAGE + ".ExternalIdentityResolverPort")
				.check(CLASSES);
	}

	@Test
	void commandResultReadDependsOnlyOnImmutableResultStore() {
		String service = ROOT_PACKAGE + ".engine.read.commandresult.GetCommandResultService";
		Set<String> dependencies = directDependencyNames(service);
		assertTrue(dependencies.contains(
				ROOT_PACKAGE + ".engine.read.commandresult.CommandResultStore"));
		assertFalse(dependencies.stream().anyMatch(name -> name.contains("Binding")),
				"Command result GET must not resolve current binding");
		Set<String> forbidden = dependencies.stream()
				.filter(name -> name.equals(ROOT_PACKAGE + ".engine.consume.command.port.out.CommandOutcomeQueryPort")
						|| name.startsWith(ROOT_PACKAGE + ".infra.")
						|| name.startsWith(ROOT_PACKAGE + ".domain.consumption.")
						|| name.contains("RecordedCommand")
						|| name.contains("Projection"))
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), forbidden,
				"COMMAND_RESULT GET must read only its immutable Result store");

		noClasses()
				.that().resideInAPackage(ROOT_PACKAGE + ".supra.http.read..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".infra.persistence.primary.jpa..",
						ROOT_PACKAGE + ".infra.tx..",
						ROOT_PACKAGE + ".engine.consume.command.port.out..",
						ROOT_PACKAGE + ".domain.consumption..")
				.check(CLASSES);
		Set<String> controllerDependencies = directDependencyNames(
				ROOT_PACKAGE + ".supra.http.read.CommandResultController");
		assertFalse(controllerDependencies.contains(
				USER_IDENTITY_DOMAIN_PACKAGE + ".ExternalIdentityResolverPort"));
		assertFalse(controllerDependencies.contains(
				ROOT_PACKAGE + ".port.transaction.TransactionRunner"));
		Set<String> bindingControllerDependencies = directDependencyNames(
				ROOT_PACKAGE + ".supra.http.read.CurrentBindingController");
		assertTrue(bindingControllerDependencies.contains(
				ROOT_PACKAGE + ".engine.read.currentbinding.GetCurrentBindingUseCase"));
		assertFalse(bindingControllerDependencies.contains(
				ROOT_PACKAGE + ".engine.materialize.currentbinding.port.CurrentBindingWritePort"));
		assertEquals(Set.of("owner", "outcome"), fieldNames(
				ROOT_PACKAGE + ".engine.read.commandresult.ImmutableCommandResult"));
	}

	@Test
	void commandConsumptionRuntimeComposesGenericPollingWithoutKnowingCommandPayloadsOrHttp() {
		String runtimePackage = ROOT_PACKAGE + ".runtime.command.consumption";
		noClasses()
				.that().haveSimpleName("CommandConsumptionRuntimeConfiguration")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.consume.command.model..",
						ROOT_PACKAGE + ".engine.consume.command.decode..",
						ROOT_PACKAGE + ".engine.consume.command.dispatch..",
						ROOT_PACKAGE + ".engine.pot..",
						ROOT_PACKAGE + ".domain.pot..",
						ROOT_PACKAGE + ".supra.http..")
				.check(CLASSES);

		noClasses()
				.that().resideInAPackage(ROOT_PACKAGE + ".orchestrator.poll.consumption..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.consume.command..",
						ROOT_PACKAGE + ".supra.consume.command..",
						ROOT_PACKAGE + ".engine.service.consumption..")
				.check(CLASSES);
	}

	@Test
	void commandPersistenceDependsInwardAndIntroducesNoJpaEntity() {
		String persistencePackage = ROOT_PACKAGE + ".infra.persistence.primary.jpa";
		String commandPersistencePackage = persistencePackage + ".adapter.command";
		String commandRepositoryPackage = persistencePackage + ".repository.command";
		Set<String> dependencies = dependenciesOutside(
				persistencePackage + ".adapter.command",
				Set.of(commandPersistencePackage, commandRepositoryPackage,
						ROOT_PACKAGE + ".engine.consume.command", ROOT_PACKAGE + ".engine.admit.command.port.out",
						ROOT_PACKAGE + ".contracts.command", ROOT_PACKAGE + ".domain.authorization",
						ROOT_PACKAGE + ".engine.read.commandresult",
                        ROOT_PACKAGE + ".engine.materialize.commandresult",
						ROOT_PACKAGE + ".domain.event", USER_IDENTITY_DOMAIN_PACKAGE,
						ROOT_PACKAGE + ".contracts.observability.trace",
						"org.springframework", "com.fasterxml.jackson"));
		assertEquals(Set.of(), dependencies,
				"Command persistence may depend only on generic Command contracts and infrastructure libraries");

		Set<String> commandEntities = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(persistencePackage))
				.filter(javaClass -> javaClass.getSimpleName().contains("RecordedCommandEntity"))
				.map(javaClass -> javaClass.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), commandEntities,
				"Recorded Commands use explicit JDBC and must not acquire a mutable JPA entity");
	}

	@Test
	void lkvSupraDependsOnlyOnItsSpecializedEngineAndGenericConsumption() {
		noClasses()
				.that().resideInAnyPackage(ROOT_PACKAGE + ".supra.consume.lkv..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.consume.command..",
						ROOT_PACKAGE + ".engine.consume.projectiontask..",
						ROOT_PACKAGE + ".engine.produce.projectiontask..",
						ROOT_PACKAGE + ".port.projection..",
						ROOT_PACKAGE + ".infra..",
						ROOT_PACKAGE + ".runtime..",
						"org.springframework..",
						"jakarta.persistence..",
						"java.sql..")
				.check(CLASSES);

		Set<String> recordedEventFields = CLASSES.get(ROOT_PACKAGE + ".domain.pot.event.RecordedEvent")
				.getAllFields().stream()
				.map(field -> field.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of("eventId", "event", "recordedAt", "traceMetadata"), recordedEventFields,
				"RecordedEvent must not carry pipeline consumption state");
	}

	@Test
	void recordedProcessingModelsDoNotCarryClaimOrLeaseState() {
		assertEquals(Set.of("eventId", "event", "recordedAt", "traceMetadata"),
				fieldNames(ROOT_PACKAGE + ".domain.pot.event.RecordedEvent"));
	}

	@Test
	void processingReconciliationDoesNotDependOnExecutionGuardsAndObsoleteDecoratorsAreGone() {
		Set<String> obsoleteDecorators = Set.of(
				"TransactionalCompleteTaskProcessingUseCase",
				"TransactionalFailTaskProcessingUseCase");
		Set<String> presentObsoleteDecorators = CLASSES.stream()
				.map(javaClass -> javaClass.getSimpleName())
				.filter(obsoleteDecorators::contains)
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), presentObsoleteDecorators,
				"terminal lifecycle and durable status must not share an outer processing transaction");
	}

	@Test
	void functionalUseCaseFamiliesDoNotDependOnConsumptionDomain() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.consume.command.pot..",
						ROOT_PACKAGE + ".engine.consume.command.pot.dispatch..",
						ROOT_PACKAGE + ".engine.service.transaction.command..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".domain.consumption.claim..",
						ROOT_PACKAGE + ".domain.consumption.key..",
						ROOT_PACKAGE + ".domain.consumption.provenance..")
				.check(CLASSES);
	}

	@Test
	void functionalBalanceProjectionDoesNotDependOnWorkersOrConsumption() {
		noClasses()
				.that().resideInAnyPackage(
						ROOT_PACKAGE + ".projector.pot..")
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
	void httpControllersDoNotDependOnJpa() {
		noClasses()
				.that().resideInAPackage(ROOT_PACKAGE + ".supra.http..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".infra.persistence.primary.jpa..",
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
	void commandLocatorOwnsConsumptionIdentityAndStaysIndependentFromPotAndOuterLayers() {
		String locatorPackage = ROOT_PACKAGE + ".supra.consume.command..";
		noClasses()
				.that().resideInAPackage(locatorPackage)
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".domain.pot..",
						ROOT_PACKAGE + ".engine.consume.command.pot.dispatch..",
						ROOT_PACKAGE + ".infra..",
						ROOT_PACKAGE + ".runtime..",
						"org.springframework..",
						"jakarta.persistence..",
						"com.fasterxml.jackson..")
				.check(CLASSES);

		Set<String> keyOwners = CLASSES.stream()
				.filter(javaClass -> javaClass.getSimpleName().equals("CommandConsumptionKeys"))
				.map(javaClass -> javaClass.getPackageName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(ROOT_PACKAGE + ".supra.consume.command"), keyOwners,
				"the Command locator specialization must own its ConsumptionKey convention");
	}

	@Test
	void projectionNDoesNotRequireLatestKnownVersionAtLeastN() {
		Set<String> projectionRuntimePackages = Set.of(
				ROOT_PACKAGE + ".engine.projection.task",
				ROOT_PACKAGE + ".engine.projection.balance",
				ROOT_PACKAGE + ".engine.projection.pot",
				ROOT_PACKAGE + ".orchestrator.consumption",
				ROOT_PACKAGE + ".runtime.task.consumption");
		Set<String> latestKnownVersionTypes = Set.of(
				ROOT_PACKAGE + ".engine.materialize.latestknownversion.LatestKnownVersion",
				ROOT_PACKAGE + ".engine.materialize.latestknownversion.AdvanceLatestKnownVersionUseCase",
				ROOT_PACKAGE + ".engine.materialize.latestknownversion.LatestKnownVersionPersistencePort",
				ROOT_PACKAGE + ".infra.persistence.read.jdbc.JdbcLatestKnownVersionAdapter");
		Set<String> forbiddenDependencies = CLASSES.stream()
				.filter(javaClass -> projectionRuntimePackages.stream()
						.anyMatch(prefix -> javaClass.getPackageName().startsWith(prefix)))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.filter(dependency -> latestKnownVersionTypes.contains(dependency.getTargetClass().getName()))
				.map(HexagonalArchitectureTest::dependencyKey)
				.collect(Collectors.toUnmodifiableSet());

		assertEquals(Set.of(), forbiddenDependencies,
				"Task acquisition, projector execution and artifact production must not use "
						+ "LatestKnownVersion as a gate: latestKnownVersion=N-1 must allow projection N");
	}

	@Test
	void latestKnownVersionIsADirectGenericConsumptionWithoutTaskOrProjectionArtifacts() {
		String locator = ROOT_PACKAGE
				+ ".supra.consume.lkv.LatestKnownVersionConsumptionLocator";
		Set<String> dependencies = directDependencyNames(locator);

		assertTrue(dependencies.stream().anyMatch(name -> name.endsWith(".ConsumptionLocator")));
		assertTrue(dependencies.stream().anyMatch(name -> name.endsWith(".AdvanceLatestKnownVersionUseCase")));
		assertTrue(dependencies.stream().anyMatch(name -> name.endsWith(".LatestKnownVersionEventDiscoveryPort")));
		assertFalse(dependencies.stream().anyMatch(name -> name.contains(".task.")));
		assertFalse(dependencies.stream().anyMatch(name -> name.endsWith(".ProjectionHead")));
		assertFalse(dependencies.stream().anyMatch(name -> name.contains("ProjectionArtifact")));
		assertFalse(dependencies.stream().anyMatch(name -> name.contains("TaskExecutionReport")));
	}

	@Test
	void legacyTaskRuntimeTypesAreAbsent() {
		Set<String> forbiddenPackageFragments = Set.of(
				".domain.task", ".engine.processing.task", ".engine.taskexecution",
				".engine.port.in.taskexecution", ".engine.port.out.processing.task",
				".locator.consumption.task", ".pipeline.balance", ".pipeline.pot");
		Set<String> forbiddenSimpleNames = Set.of(
				"TaskConsumptionRuntimeConfiguration", "TaskConsumptionProperties",
				"JpaTaskConsumptionDiscoveryAdapter", "JpaTaskPort", "JpaPipelineTaskEntity",
				"JpaImmutableBalanceProjectionAdapter", "JdbcPotProjectionArtifactWriter",
				"ProjectionMaterializationService", "ProjectionFailureService");
		Set<String> present = CLASSES.stream()
				.filter(javaClass -> forbiddenPackageFragments.stream()
						.anyMatch(fragment -> javaClass.getPackageName().contains(fragment))
						|| forbiddenSimpleNames.contains(javaClass.getSimpleName()))
				.map(javaClass -> javaClass.getName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), present, "PCL.3 legacy Task runtime and writers must not return");
	}

	@Test
	void deadEventSchedulingTypesAreAbsent() {
		Set<String> forbiddenTypes = Set.of(
				"EventConsumptionLocator", "EventConsumptionDiscoveryPort", "EventSchedulingCandidate",
				"EventSchedulingOrderingKey", "ScheduleProjectionTasksForEventUseCase",
				"JpaTaskCreationAdapter", "CanonicalProjectionTaskScheduler",
				"MeteredProjectionTaskScheduler", "PotTaskCreationStrategy",
				"BalanceTaskCreationStrategy");
		Set<String> present = CLASSES.stream()
				.map(javaClass -> javaClass.getSimpleName())
				.filter(forbiddenTypes::contains)
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), present, "PCL.1 legacy Event scheduling must not return");
	}

	@Test
	void synchronousCommandDispatchIsGoneWhilePotBusinessPortsRemain() {
		Set<String> legacyCommandTypes = Set.of(
				"ExecuteCommandUseCase", "ExecuteCommandInput", "ExecuteCommandService",
				"CommandIntent", "CommandUseCaseFactory", "UnsupportedCommandIntentException",
				"TransactionalCreatePotUseCase", "TransactionalCreateExpenseUseCase",
				"TransactionalAddPotShareholdersUseCase", "TransactionalDeletePotUseCase",
				"TransactionalDeleteExpenseUseCase", "TransactionalUpdatePotDetailsUseCase",
				"TransactionalUpdateExpenseDetailsUseCase", "TransactionalUpdateExpenseSharesUseCase",
				"TransactionalUpdatePotShareholdersDetailsUseCase",
				"TransactionalUpdatePotShareholdersWeightsUseCase");
		assertEquals(Set.of(), CLASSES.stream().map(javaClass -> javaClass.getSimpleName())
				.filter(legacyCommandTypes::contains).collect(Collectors.toUnmodifiableSet()));

		Set<String> businessPorts = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().equals(
						ROOT_PACKAGE + ".engine.write.pot.usecase"))
				.map(javaClass -> javaClass.getSimpleName())
				.collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(
				"CreatePotUseCase", "CreateExpenseUseCase", "AddPotShareholdersUseCase",
				"DeletePotUseCase", "DeleteExpenseUseCase", "UpdatePotDetailsUseCase",
				"UpdateExpenseDetailsUseCase", "UpdateExpenseSharesUseCase",
				"UpdatePotShareholdersDetailsUseCase", "UpdatePotShareholdersWeightsUseCase"),
				businessPorts);

		Map<String, String> adapterPorts = Map.of(
				"CreatePotCommandUseCaseAdapter", "CreatePotUseCase",
				"CreateExpenseCommandUseCaseAdapter", "CreateExpenseUseCase",
				"AddPotShareholdersCommandUseCaseAdapter", "AddPotShareholdersUseCase",
				"DeletePotCommandUseCaseAdapter", "DeletePotUseCase",
				"DeleteExpenseCommandUseCaseAdapter", "DeleteExpenseUseCase",
				"UpdatePotDetailsCommandUseCaseAdapter", "UpdatePotDetailsUseCase",
				"UpdateExpenseDetailsCommandUseCaseAdapter", "UpdateExpenseDetailsUseCase",
				"UpdateExpenseSharesCommandUseCaseAdapter", "UpdateExpenseSharesUseCase",
				"UpdatePotShareholdersDetailsCommandUseCaseAdapter", "UpdatePotShareholdersDetailsUseCase",
				"UpdatePotShareholdersWeightsCommandUseCaseAdapter", "UpdatePotShareholdersWeightsUseCase");
		adapterPorts.forEach((adapter, port) -> assertTrue(directDependencyNames(
				ROOT_PACKAGE + ".engine.consume.command.pot.dispatch." + adapter).stream()
				.anyMatch(name -> name.endsWith("." + port)), adapter + " must use " + port));
	}

	@Test
	void httpAdmissionCannotMutateThePotWriteModelDirectly() {
		noClasses()
				.that().resideInAPackage(ROOT_PACKAGE + ".supra.http.write..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".engine.write.pot.usecase..",
						ROOT_PACKAGE + ".engine.consume.command.pot.dispatch..",
						ROOT_PACKAGE + ".runtime.command.consumption..",
						ROOT_PACKAGE + ".supra.consume.command..")
				.check(CLASSES);

		Set<String> asyncControllerDependencies = directDependencyNames(
				ROOT_PACKAGE + ".supra.http.write.AsyncCommandController");
		assertTrue(asyncControllerDependencies.stream()
				.anyMatch(name -> name.endsWith(".SubmitRecordedCommandUseCase")));
	}

	@Test
	void genericConsumptionPullLayersStayIndependentFromWorkFamiliesAndFrameworks() {
		noClasses()
				.that().resideInAPackage(ROOT_PACKAGE + ".orchestrator.consumption..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".domain.task..",
						ROOT_PACKAGE + ".domain.pipeline..",
						ROOT_PACKAGE + ".domain.pot..",
						ROOT_PACKAGE + ".engine..command..",
						ROOT_PACKAGE + ".engine..processing.event..",
						ROOT_PACKAGE + ".engine..taskcreation..",
						ROOT_PACKAGE + ".engine..taskexecution..",
						ROOT_PACKAGE + ".locator..",
						ROOT_PACKAGE + ".supra..",
						ROOT_PACKAGE + ".runtime..",
						ROOT_PACKAGE + ".infra..",
						ROOT_PACKAGE + ".engine.taskcreation..",
						"org.springframework..",
						"jakarta.persistence..",
						"io.nats..")
				.check(CLASSES);

		noClasses()
				.that().resideInAPackage(ROOT_PACKAGE + ".orchestrator.poll.consumption..")
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".locator..",
						ROOT_PACKAGE + ".domain.task..",
						ROOT_PACKAGE + ".domain.pipeline..",
						ROOT_PACKAGE + ".domain.pot..",
						ROOT_PACKAGE + ".engine..processing..",
						ROOT_PACKAGE + ".engine..taskcreation..",
						ROOT_PACKAGE + ".runtime..",
						ROOT_PACKAGE + ".infra..",
						"org.springframework..",
						"jakarta.persistence..",
						"io.nats..")
				.check(CLASSES);

		noClasses()
				.that().resideOutsideOfPackage(ROOT_PACKAGE + ".runtime.event.consumption..")
				.should().dependOnClassesThat().resideInAPackage(
						ROOT_PACKAGE + ".runtime.event.consumption..")
				.check(CLASSES);

		noClasses()
				.that().resideOutsideOfPackage(ROOT_PACKAGE + ".runtime.task.consumption..")
				.should().dependOnClassesThat().resideInAPackage(
						ROOT_PACKAGE + ".runtime.task.consumption..")
				.check(CLASSES);
	}

	@Test
	void primaryLkvAdaptersDependOnlyOnTheFinalLkvSupraContracts() {
		Set<String> actualDependencies = CLASSES.stream()
				.filter(javaClass -> javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".infra.persistence.primary.jpa"))
				.flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
				.filter(dependency -> dependency.getTargetClass().getPackageName().startsWith(ROOT_PACKAGE + ".supra"))
				.map(HexagonalArchitectureTest::dependencyKey)
				.collect(Collectors.toUnmodifiableSet());

		assertFalse(actualDependencies.isEmpty(), "the LKV Event adapters must remain visible to this guard");
		assertTrue(actualDependencies.stream().allMatch(dependency ->
				dependency.startsWith(ROOT_PACKAGE + ".infra.persistence.primary.jpa.adapter.processing.event.")
				|| dependency.startsWith(ROOT_PACKAGE + ".infra.persistence.primary.jpa.repository.consumption.")));
		assertTrue(actualDependencies.stream().allMatch(dependency ->
				dependency.contains(" -> " + ROOT_PACKAGE + ".supra.consume.lkv.")));
	}

	@Test
	void readPersistenceDoesNotDependOnPrimaryPersistenceOrOuterLayers() {
		noClasses()
				.that().resideInAPackage(INFRA_READ_PERSISTENCE_PACKAGE)
				.should().dependOnClassesThat().resideInAnyPackage(
						INFRA_PERSISTENCE_PACKAGE,
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..")
				.check(CLASSES);
	}

	@Test
	void canonicalProjectionPersistenceDependsOnlyOnProjectionContractsAndInfrastructureLibraries() {
		noClasses()
				.that().resideInAPackage(INFRA_PROJECTION_PERSISTENCE_PACKAGE)
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".domain.authorization..",
						ROOT_PACKAGE + ".domain.pipeline..",
						ROOT_PACKAGE + ".domain.projection.legacy..",
						ROOT_PACKAGE + ".infra.persistence.read.jdbc..",
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..")
				.check(CLASSES);
	}

	@Test
	void genericReadProjectionFoundationKeepsFunctionalSemanticsInside() {
		noClasses()
				.that().resideInAPackage(PROJECTION_DOMAIN_PACKAGE)
				.should().dependOnClassesThat().resideInAnyPackage(
						ENGINE_PACKAGE,
						ROOT_PACKAGE + ".infra..",
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..",
						"org.springframework..",
						"java.sql..",
						"jakarta.persistence..")
				.check(CLASSES);

		noClasses()
				.that().resideInAPackage(READ_PROJECTION_ENGINE_PACKAGE)
				.should().dependOnClassesThat().resideInAnyPackage(
						ROOT_PACKAGE + ".infra..",
						SUPRA_PACKAGE,
						ROOT_PACKAGE + ".runtime..",
						ROOT_PACKAGE + ".domain.consumption..",
						ROOT_PACKAGE + ".domain.task..",
						"org.springframework..",
						"java.sql..",
						"jakarta.persistence..")
				.check(CLASSES);
	}

	private static String dependencyKey(Dependency dependency) {
		return dependency.getOriginClass().getName() + " -> " + dependency.getTargetClass().getName();
	}

	private static Set<String> fieldNames(String className) {
		return CLASSES.get(className).getAllFields().stream()
				.map(field -> field.getName())
				.collect(Collectors.toUnmodifiableSet());
	}

	private static Set<String> directDependencyNames(String className) {
		return CLASSES.get(className).getDirectDependenciesFromSelf().stream()
				.map(dependency -> dependency.getTargetClass().getName())
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
