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

loom {
    runs {
        create("uiPreview") {
            inherit(getByName("clientGameTest"))
            displayName.set("Minecraft Assistant UI Preview")
            environmentVars.put("MINECRAFT_ASSISTANT_UI_PREVIEW", "true")
            environmentVars.put(
                "MINECRAFT_ASSISTANT_UI_PREVIEW_OUTPUT",
                layout.buildDirectory.dir("ui-previews").get().asFile.absolutePath
            )
            runDirectory.set(layout.buildDirectory.dir("run/uiPreview"))
            generateRunConfig.set(false)
        }
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
    implementation(project(":tool-mediawiki"))

    include(project(":assistant-core"))
    include(project(":provider-openrouter"))
    include(project(":tool-mediawiki"))
    include("com.fasterxml.jackson.core:jackson-annotations:2.22")
    include("com.fasterxml.jackson.core:jackson-core:2.22.2")
    include("com.fasterxml.jackson.core:jackson-databind:2.22.2")
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
