import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.scip_code.scip_java.buildlogic.JavacInternals

plugins {
    id("scip.java-library")
    id("scip.shadow-producer")
    id("scip.maven-publish")
}

description = "A javac plugin to emit SCIP information"

dependencies {
    api(project(":scip-shared"))
}

tasks.named<JavaCompile>("compileJava") {
    options.isDebug = true
    val emptyProcessorPath = layout.buildDirectory.dir("empty-processorpath")
    options.annotationProcessorPath = files(emptyProcessorPath)
    doFirst {
        emptyProcessorPath.get().asFile.mkdirs()
    }
}

tasks.named<JavaCompile>("compileTestJava") {
    // Tests use `@ClientCodeWrapper.Trusted` (an internal javac API) to construct file objects
    // that mimic Bazel's compiler. `--add-exports` is incompatible with `--release`.
    options.release.set(null as Int?)
    options.compilerArgs.addAll(JavacInternals.jvmOptions(rootDir))
}

tasks.named<Test>("test") {
    jvmArgs(JavacInternals.jvmOptions(rootDir))
}

tasks.named<ShadowJar>("shadowJar") {
    mergeServiceFiles()
    relocate("com.google", "org.scip_code.scip_java.shaded.com.google")
    relocate("google", "org.scip_code.scip_java.shaded.google")
    relocate("org.scip_code.scip_java", "org.scip_code.scip_java.shaded.org.scip_code.scip_java") {
        exclude("org.scip_code.scip_java.javac.ScipPlugin")
        exclude("org.scip_code.scip_java.javac.InjectScipOptions")
    }
}

// Debug variant: the same fat jar but WITHOUT relocation, so that classes keep
// their original package names (org.scip_code.scip_java.javac.ScipVisitor, ...).
// IDE debuggers (e.g. IntelliJ) set breakpoints against the project's source
// files, so this jar lets breakpoints hit without the `shaded.` name mapping.
val shadowJarDebug by tasks.registering(ShadowJar::class) {
    dependsOn(tasks.classes)
    archiveClassifier.set("all-debug")
    mergeServiceFiles()
    from(sourceSets.main.get().output)
    configurations = listOf(project.configurations.runtimeClasspath.get())
}
