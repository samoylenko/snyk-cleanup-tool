group = "dev.samoylenko"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()

    maven {
        name = "Central Snapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
}

plugins {
    application

    alias(libs.plugins.kotlin.plugin.jvm)
}

dependencies {
    implementation(libs.client.snyk)
    implementation(libs.clikt)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks {
    test {
        useJUnitPlatform()
        testLogging.showStandardStreams = true
    }
}

application {
    mainClass.set("dev.samoylenko.client.snyk.cli.AppKt")
}
