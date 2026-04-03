plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

intellij {
    version.set("2024.1.7")
    type.set("IC")
    plugins.set(listOf("java"))
    downloadSources.set(false)
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }

    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("251.*")
    }

    // Disable searchable options to speed up build
    buildSearchableOptions {
        enabled = false
    }
}
