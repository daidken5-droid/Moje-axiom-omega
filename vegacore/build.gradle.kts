import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// VEGA – čisté jádro (bez Androidu): nástroje, postupy, paměť, router, pracovní prostor. Staví na :core z AVIORu.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core"))
    // ověřovač opory ve zdroji: stejné Java API ai.onnxruntime – na Androidu dodá onnxruntime-android (aplikace), v testech JVM verze
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
