import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("scip.java-library")
    id("scip.kotlin-jvm")
    id("scip.shadow-producer")
    id("scip.maven-publish")
}

description = "A kotlinc plugin to emit SCIP information"

dependencies {
    implementation(project(":scip-shared"))
    implementation(libs.scip.kotlin.bindings)
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.kotlin.compiler.embeddable)

    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kctfork.core)
}

tasks.named<Test>("test") {
    maxHeapSize = "2g"
}

tasks.named<ShadowJar>("shadowJar") {
    mergeServiceFiles()
}

// Debug variant: the same fat jar but WITHOUT relocation, so that classes keep
// their original package names (org.scip_code.scip_java.kotlinc.ScipVisitor, ...).
// IDE debuggers (e.g. IntelliJ) set breakpoints against the project's source
// files, so this jar lets breakpoints hit without a `shaded.` name mapping.
val shadowJarDebug by tasks.registering(ShadowJar::class) {
    dependsOn(tasks.classes)
    archiveClassifier.set("all-debug")
    mergeServiceFiles()
    from(sourceSets.main.get().output)
    configurations = listOf(project.configurations.runtimeClasspath.get())
}
