dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "db-scheduler-console"

include(
    "console-core",
    "console-spring-boot-3-starter",
    "console-spring-boot-4-starter",
    "examples:boot3-example",
    "examples:boot4-example",
)
