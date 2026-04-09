plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.example"
version = "2.1.2"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
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
        untilBuild.set("")
    }

    // Disable searchable options to speed up build
    buildSearchableOptions {
        enabled = false
    }

    test {
        useJUnitPlatform()
    }
}
