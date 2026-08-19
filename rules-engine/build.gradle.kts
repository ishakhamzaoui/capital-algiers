import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    jacoco
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":economy-data"))
    testImplementation(libs.junit)
}

jacoco {
    toolVersion = "0.8.15"
}

tasks.test {
    useJUnit()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Fails the build if rules-engine line coverage drops below the M1 exit
// criterion (>=90% line coverage, DevelopmentRoadmap.md M1).
tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification90") {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
    executionData(tasks.test.get())
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(files(layout.buildDirectory.dir("classes/kotlin/main")))
}
