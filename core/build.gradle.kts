import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // JSON bez code-generation pluginu (JsonElement API) – funguje na JVM i Androidu.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Oficiální Anthropic SDK pro Claude.
    api("com.anthropic:anthropic-java:3.3.0")
    // Podepisování APK vytvořených v telefonu (čistá Java, funguje na Androidu i JVM).
    api("com.android.tools.build:apksig:8.7.3")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
