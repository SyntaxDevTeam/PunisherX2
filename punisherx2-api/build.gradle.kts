plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    api(kotlin("stdlib"))

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
