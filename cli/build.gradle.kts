plugins {
    application
}

dependencies {
    implementation(project(":assistant-core"))
    implementation(project(":provider-codex"))
    implementation(project(":tool-mediawiki"))
}

application {
    mainClass.set("dev.zubinjha.minecraftassistant.cli.Main")
    applicationName = "minecraft-assistant"
}
