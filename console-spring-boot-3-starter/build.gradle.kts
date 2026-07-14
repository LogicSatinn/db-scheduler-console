plugins {
    `java-library`
    alias(libs.plugins.mavenPublish)
}

mavenPublishing {
    pom {
        name.set("db-scheduler Console Spring Boot 3 Starter")
        description.set(
            "Spring Boot 3 starter for the db-scheduler web console: auto-configures " +
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
    api(libs.dbSchedulerBoot3Starter)

    compileOnly(platform(libs.springBoot3Bom))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-webmvc")

    testImplementation(platform(libs.springBoot3Bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
