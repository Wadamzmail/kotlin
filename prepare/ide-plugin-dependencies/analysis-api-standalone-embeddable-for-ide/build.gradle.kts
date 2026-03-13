import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
}

val intellijVersion = rootProject.extra["versions.intellijSdk"]

val embeddableJar = configurations.getOrCreate("embeddableJar").apply {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }

    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
}

dependencies {
    embeddableJar(intellijCore())
    embeddableJar(libs.intellij.fastutil)
    embeddableJar(commonDependency("com.fasterxml:aalto-xml"))
    embeddableJar(project(":analysis:analysis-api-standalone:analysis-api-standalone-base",))
    embeddableJar(project(":analysis:analysis-api-standalone:analysis-api-fir-standalone-base",))
    embeddableJar(project(":analysis:analysis-api-standalone",))
}

val packagesToRelocate =
    listOf(
        "com.google",
        "com.sampullara",
        "org.apache",
        "org.jdom",
        "org.picocontainer",
        "org.jline",
        "org.fusesource",
        "net.jpountz",
        "it.unimi.dsi.fastutil",
        "javax.annotation",
        "kotlinx.collections.immutable",
        "com.fasterxml",
        "org.codehaus",
        "io.opentelemetry",
        "io.vavr",
    )

tasks.register<ShadowJar>("embeddable") {
    destinationDirectory.set(project.layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    configurations = listOf(embeddableJar)

    relocate("com.google.protobuf", "org.jetbrains.kotlin.protobuf")
    relocate("com.intellij", "$kotlinEmbeddableRootPackage.com.intellij") {
        // These are not real packages, but important string constants which are used by xml-reader.
        exclude("com.intellij.projectService")
        exclude("com.intellij.applicationService")
    }

    packagesToRelocate.forEach {
        relocate(it, "$kotlinEmbeddableRootPackage.$it")
    }

    relocate("javax.inject", "$kotlinEmbeddableRootPackage.javax.inject")
    relocate("org.fusesource", "$kotlinEmbeddableRootPackage.org.fusesource") {
        // TODO: remove "it." after #KT-12848 get addressed
        exclude("org.fusesource.jansi.internal.CLibrary")
    }

    relocate("javax.xml", "jaxp.xml")

    exclude("org/checkerframework/**",)
    exclude("org/intellij/lang/annotations",)
    exclude("com/sun/jna/**")
    exclude("org/jetbrains/annotations/**")
    mergeServiceFiles()
}
