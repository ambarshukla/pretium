import com.diffplug.gradle.spotless.SpotlessExtension

// Root build. Holds only configuration that must be identical in every module:
// the Java toolchain, compiler strictness, the test framework and formatting.
// Modules declare their own dependencies and nothing else.

plugins {
    alias(libs.plugins.springBoot) apply false
    alias(libs.plugins.springDependencyManagement) apply false
    alias(libs.plugins.spotless)
}

allprojects {
    group = "io.pretium"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    // A toolchain, not `sourceCompatibility`: a local build and CI compile
    // with the same JDK whatever happens to be on PATH.
    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        // -parameters keeps constructor parameter names in the bytecode, which
        // Spring and Jackson use to bind records without annotations.
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Werror", "-parameters"))
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    configure<SpotlessExtension> {
        java {
            googleJavaFormat()
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}
