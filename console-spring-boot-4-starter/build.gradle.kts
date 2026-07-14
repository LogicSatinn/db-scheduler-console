plugins {
    `java-library`
    alias(libs.plugins.mavenPublish)
}

mavenPublishing {
    coordinates(artifactId = "db-scheduler-console-spring-boot-4-starter")
    pom {
        name.set("db-scheduler Console Spring Boot 4 Starter")
        description.set(
            "Spring Boot 4 starter for the db-scheduler web console: auto-configures " +
                "the dashboard on top of db-scheduler's Spring Boot integration."
        )
    }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
    withSourcesJar()
}

dependencies {
    api(project(":console-core"))
    api(libs.dbSchedulerBoot4Starter)

    compileOnly(platform(libs.springBoot4Bom))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-webmvc")

    testImplementation(platform(libs.springBoot4Bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
