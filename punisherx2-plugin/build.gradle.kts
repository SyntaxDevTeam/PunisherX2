import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":punisherx2-api"))

    implementation("com.github.ben-manes.caffeine:caffeine:3.2.2")

    compileOnly("io.papermc.paper:paper-api:1.21.3-R0.1-SNAPSHOT")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.mockk:mockk:1.13.12")
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.withType<ShadowJar>().configureEach {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.jar { enabled = false }

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}
