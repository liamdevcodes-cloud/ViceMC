import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("com.gradleup.shadow") version "8.3.6"
    java
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.46.1.3") {
        exclude(group = "org.slf4j")
    }
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.shadowJar {
    archiveBaseName.set("ViceCore")
    archiveClassifier.set("")
    archiveVersion.set("")
}

// Verzamelt alle plugin-jars (core + modules) in de output-map (sync = verwijdert stale jars)
val collectJars by tasks.registering(Sync::class) {
    group = "build"
    description = "Verzamelt alle plugin-jars in de output-map"

    dependsOn(tasks.shadowJar)
    dependsOn(subprojects.map { it.tasks.named("jar") })

    into(rootProject.layout.projectDirectory.dir("output"))
    from(tasks.shadowJar)
    // Alleen echte modules (met plugin.yml), niet het lege :modules container-project
    from(subprojects
        .filter { it.file("src/main/resources/plugin.yml").exists() }
        .map { it.tasks.named("jar") })
}

tasks.build {
    dependsOn(tasks.shadowJar, collectJars)
}

// Module-subprojecten: compileren als Vice<Naam>-1.0.0.jar tegen de core (root project)
subprojects {
    apply(plugin = "java")

    version = "1.0.0"
    base.archivesName = "Vice" + name.replaceFirstChar { it.uppercase() }

    repositories {
        maven("https://repo.papermc.io/repository/maven-public/")
        mavenCentral()
    }

    dependencies {
        compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
