import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "minecraft-assistant-test"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

base {
    archivesName.set("minecraft-assistant")
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.160.0+26.2")

    implementation(project(":assistant-core"))
    implementation(project(":provider-openrouter"))
    implementation(project(":tool-mcp")) {
        exclude(group = "org.slf4j", module = "slf4j-nop")
    }

    include(project(":assistant-core"))
    include(project(":provider-openrouter"))
    include(project(":tool-mcp"))
    include("com.fasterxml.jackson.core:jackson-annotations:2.22")
    include("com.fasterxml.jackson.core:jackson-core:2.22.2")
    include("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    include("io.modelcontextprotocol.sdk:mcp:2.0.1")
    include("io.modelcontextprotocol.sdk:mcp-json-jackson3:2.0.1")
    include("io.modelcontextprotocol.sdk:mcp-core:2.0.1")
    include("io.projectreactor:reactor-core:3.7.0")
    include("org.reactivestreams:reactive-streams:1.0.4")
    include("tools.jackson.core:jackson-core:3.1.4")
    include("tools.jackson.core:jackson-databind:3.1.4")
    include("tools.jackson.dataformat:jackson-dataformat-yaml:3.1.4")
    include("org.snakeyaml:snakeyaml-engine:3.0.1")
    include("com.networknt:json-schema-validator:3.0.6")
    include("com.ethlo.time:itu:1.14.0")
}

val modVersion = version.toString()

tasks.processResources {
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}
