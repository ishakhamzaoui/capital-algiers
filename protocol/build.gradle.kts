plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.jetbrains.kotlin.serialization)
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
    implementation(project(":rules-engine"))

    // rules-engine declares its own economy-data dependency as
    // `implementation`, not `api`, so economy-data's types (e.g. Deck,
    // referenced directly by rules-engine's GameEvent/TradeProposal) are not
    // visible on :protocol's compile classpath transitively. This adds that
    // visibility explicitly rather than changing rules-engine's dependency
    // type, since :protocol genuinely needs Deck at compile time (see
    // ClientMessage.TradeProposalRequest / EnumSerializers.kt) and widening
    // rules-engine's own dependency to `api` isn't otherwise warranted.
    implementation(project(":economy-data"))

    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}