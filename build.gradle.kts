plugins {
    base
}

allprojects {
    group = "io.github.logicsatinn"
    version = "0.1.0-SNAPSHOT"

    // Spring MVC resolves @PathVariable/@RequestParam names reflectively; without -parameters
    // (which the Spring Boot Gradle plugin would add, and which we do not apply here) those
    // names are erased and request mapping fails at runtime.
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
    }
}
