package org.hhclicker.backend.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(
        packages = "org.hhclicker.backend",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class BackendArchitectureTests {

    @ArchTest
    static final ArchRule TOP_LEVEL_MODULES_ARE_FREE_OF_CYCLES = slices()
            .matching("org.hhclicker.backend.(*)..")
            .should().beFreeOfCycles()
            .because("top-level backend modules must form an acyclic dependency graph");

    @ArchTest
    static final ArchRule SHARED_DOES_NOT_DEPEND_ON_FUNCTIONAL_MODULES = noClasses()
            .that().resideInAnyPackage("org.hhclicker.backend.shared..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..account..", "..resume..", "..search..", "..vacancy..", "..application..",
                    "..browser..", "..settings..", "..overview..", "..agent.."
            )
            .because("shared infrastructure must not import functional or transport modules");

    @ArchTest
    static final ArchRule CLASSES_OUTSIDE_AGENT_DO_NOT_DEPEND_ON_AGENT = noClasses()
            .that().resideOutsideOfPackage("org.hhclicker.backend.agent..")
            .should().dependOnClassesThat().resideInAnyPackage("org.hhclicker.backend.agent..")
            .because("agent is a transport adapter and must not leak into domain modules");

    @ArchTest
    static final ArchRule AGENT_CONTROLLERS_DO_NOT_DEPEND_ON_REPOSITORIES = noClasses()
            .that().resideInAnyPackage("org.hhclicker.backend.agent..controller..")
            .should().dependOnClassesThat().resideInAnyPackage("..repository..").allowEmptyShould(true)
            .because("agent controllers must reuse application services instead of persistence");

    @ArchTest
    static final ArchRule CLASSES_OUTSIDE_OVERVIEW_DO_NOT_DEPEND_ON_OVERVIEW = noClasses()
            .that().resideOutsideOfPackage("org.hhclicker.backend.overview..")
            .should().dependOnClassesThat().resideInAnyPackage("org.hhclicker.backend.overview..")
            .because("overview is a leaf read module and must not become a dependency");

    @ArchTest
    static final ArchRule REST_CONTROLLERS_DO_NOT_DEPEND_ON_REPOSITORIES = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().resideInAnyPackage("..repository..")
            .because("REST controllers must delegate persistence work to an application service");

    @ArchTest
    static final ArchRule REPOSITORIES_DO_NOT_DEPEND_ON_CONTROLLERS = noClasses()
            .that().resideInAnyPackage("..repository..")
            .should().dependOnClassesThat().resideInAnyPackage("..controller..")
            .because("the persistence layer must not depend on the HTTP layer");

    @ArchTest
    static final ArchRule SERVICES_DO_NOT_DEPEND_ON_CONTROLLERS = noClasses()
            .that().resideInAnyPackage("..service..")
            .should().dependOnClassesThat().resideInAnyPackage("..controller..")
            .because("application services must remain independent from HTTP controllers");

    @ArchTest
    static final ArchRule REPOSITORIES_DO_NOT_DEPEND_ON_SERVICES = noClasses()
            .that().resideInAnyPackage("..repository..")
            .should().dependOnClassesThat().resideInAnyPackage("..service..")
            .because("repositories are infrastructure dependencies of services, not their consumers");

    @ArchTest
    static final ArchRule SERVICES_DO_NOT_DEPEND_ON_SPRING_HTTP_OR_WEB = noClasses()
            .that().resideInAnyPackage("..service..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.http..", "org.springframework.web.."
            )
            .because("application services must express errors without HTTP semantics");

    @ArchTest
    static final ArchRule REPOSITORIES_DO_NOT_DEPEND_ON_SPRING_HTTP_OR_WEB = noClasses()
            .that().resideInAnyPackage("..repository..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.http..", "org.springframework.web.."
            )
            .because("repositories must not know about the HTTP transport");

    @ArchTest
    static final ArchRule SHARED_EXCEPTIONS_DO_NOT_DEPEND_ON_FUNCTIONAL_EXCEPTIONS = noClasses()
            .that().resideInAnyPackage("..shared.exception..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..account.exception..", "..resume.exception..", "..search.exception..",
                    "..vacancy.exception..", "..application.exception..", "..browser.exception..",
                    "..settings.exception..", "..agent.exception.."
            )
            .because("the shared error boundary must not import functional modules");
}
