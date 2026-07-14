import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    base
    alias(libs.plugins.mavenPublish) apply false
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

// Shared Maven Central publication config for the library modules (the modules that
// apply the vanniktech plugin). Example apps never apply it and are never published.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()

            pom {
                url.set("https://github.com/logicsatinn/db-scheduler-console")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("logicsatinn")
                        name.set("Calvin Jackson")
                        url.set("https://github.com/logicsatinn")
                    }
                }
                scm {
                    url.set("https://github.com/logicsatinn/db-scheduler-console")
                    connection.set("scm:git:git://github.com/logicsatinn/db-scheduler-console.git")
                    developerConnection.set("scm:git:ssh://git@github.com/logicsatinn/db-scheduler-console.git")
                }
            }
        }
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }
}
