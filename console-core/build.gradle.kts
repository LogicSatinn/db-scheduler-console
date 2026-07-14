plugins {
    `java-library`
    alias(libs.plugins.jte)
    alias(libs.plugins.mavenPublish)
}

mavenPublishing {
    coordinates(artifactId = "db-scheduler-console-core")
    pom {
        name.set("db-scheduler Console Core")
        description.set(
            "Core module of the db-scheduler web console: repositories, services, " +
                "controllers, and the server-rendered dashboard UI."
        )
    }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
    withSourcesJar()
}

jte {
    sourceDirectory.set(file("src/main/jte").toPath())
    contentType.set(gg.jte.ContentType.Html)
    generate()
}

dependencies {
    api(libs.dbScheduler)
    implementation(libs.jteRuntime)

    compileOnly(platform(libs.springBoot3Bom))
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("org.springframework:spring-jdbc")
    compileOnly("org.springframework.boot:spring-boot")
    compileOnly("org.springframework.security:spring-security-web")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.slf4j:slf4j-api")

    testImplementation(platform(libs.springBoot3Bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.springframework.security:spring-security-web")
    testImplementation("com.h2database:h2")
    testImplementation(libs.jsoup)
    testImplementation(libs.awaitility)
    testImplementation(libs.testcontainersJunit)
    testImplementation(libs.testcontainersPostgresql)
    testImplementation(libs.testcontainersMysql)
    testImplementation(libs.testcontainersMariadb)
    testImplementation(libs.testcontainersMssqlserver)
    testImplementation(libs.testcontainersOracleFree)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.jdbcPostgresql)
    testRuntimeOnly(libs.jdbcMysql)
    testRuntimeOnly(libs.jdbcMariadb)
    testRuntimeOnly(libs.jdbcMssql)
    testRuntimeOnly(libs.jdbcOracle)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("containers")
    }
}

val containerTest by tasks.registering(Test::class) {
    description = "Dialect matrix tests using Testcontainers"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("containers") }
    shouldRunAfter(tasks.test)
}
