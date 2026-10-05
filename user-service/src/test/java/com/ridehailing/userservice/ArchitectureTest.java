package com.ridehailing.userservice;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ridehailing.userservice");

    @Test
    void noCrossServiceDependencies() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.ridehailing.userservice..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "com.ridehailing.userservice..",
                        "java..",
                        "javax..",
                        "org.springframework..",
                        "org.apache..",
                        "com.fasterxml..",
                        "io.jsonwebtoken..",
                        "org.postgresql..",
                        "org.flywaydb..",
                        "org.testcontainers..",
                        "com.tngtech.archunit..",
                        "org.junit..",
                        "org.mockito..",
                        "org.assertj..",
                        "org.hamcrest..",
                        "jakarta..",
                        "io.micrometer..",
                        "io.netty..",
                        "io.lettuce..",
                        "com.zaxxer..",
                        "org.slf4j.."
                );

        rule.check(CLASSES);
    }

    @Test
    void noCyclesWithinService() {
        slices().matching("com.ridehailing.userservice.(*)..")
                .should().beFreeOfCycles()
                .check(CLASSES);
    }
}