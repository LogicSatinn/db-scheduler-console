plugins {
    application
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

dependencies {
    implementation(platform(libs.springBoot4Bom))
    implementation(project(":console-spring-boot-4-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

application {
    mainClass = "io.github.logicsatinn.dbscheduler.console.example.boot4.DemoApp"
}

tasks.test {
    useJUnitPlatform()
}
