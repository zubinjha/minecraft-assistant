pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "minecraft-assistant"

include(
    "assistant-core",
    "fabric",
    "provider-codex",
    "provider-openrouter",
    "tool-mcp",
    "cli",
)
