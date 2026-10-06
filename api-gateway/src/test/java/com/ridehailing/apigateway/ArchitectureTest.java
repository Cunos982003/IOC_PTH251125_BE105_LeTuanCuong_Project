package com.ridehailing.apigateway;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ridehailing.apigateway");

    @Test
    void noCrossServiceDependencies() {
        classes()
                .that().resideInAPackage("com.ridehailing.apigateway..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "com.ridehailing.apigateway..",
                        "java..",
                        "javax..",
                        "org.springframework..",
                        "org.apache..",
                        "com.fasterxml..",
                        "io.jsonwebtoken..",
                        "com.github.benmanes.caffeine..",
                        "jakarta..",
                        "io.micrometer..",
                        "org.slf4j.."
                )
                .check(CLASSES);
    }

    @Test
    void noCyclesWithinService() {
        slices().matching("com.ridehailing.apigateway.(*)..")
                .should().beFreeOfCycles()
                .check(CLASSES);
    }
}
