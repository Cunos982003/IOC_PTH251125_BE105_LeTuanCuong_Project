package com.ridehailing.dispatchservice;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ridehailing.dispatchservice");

    @Test
    void noCrossServiceDependencies() {
        classes()
                .that().resideInAPackage("com.ridehailing.dispatchservice..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "com.ridehailing.dispatchservice..",
                        "java..",
                        "javax..",
                        "org.springframework..",
                        "org.apache..",
                        "com.fasterxml..",
                        "org.postgresql..",
                        "org.flywaydb..",
                        "jakarta..",
                        "io.micrometer..",
                        "io.netty..",
                        "io.lettuce..",
                        "com.zaxxer..",
                        "org.slf4j.."
                )
                .check(CLASSES);
    }

    @Test
    void noCyclesWithinService() {
        slices().matching("com.ridehailing.dispatchservice.(*)..")
                .should().beFreeOfCycles()
                .check(CLASSES);
    }
}