# AGENTS.md

## Project

This repository contains the Minecraft Assistant project.

## Build and test

- Run all automated tests with `./gradlew test`.
- Build the Fabric 26.2 JAR with `./gradlew :fabric:build`.
- Run the isolated creative-world client test with `./gradlew :fabric:runClientGameTest`.
- Java 21 or newer is required for shared modules; Fabric 26.2 development requires Java 25. Shared production bytecode targets Java 17.

## Working guidelines

- Keep changes focused on the user's request.
- Preserve existing behavior unless a change is explicitly requested.
- Do not commit secrets, credentials, or local environment files.
- Update documentation when setup or usage changes.
- Run relevant checks before considering a change complete.
- Do not create commits or publish the repository unless explicitly requested.
- Keep Minecraft/Fabric code out of the provider, tool transport, and core agent modules.
- Never block the Minecraft render/main thread with model, network, Wiki, or authentication work.
- Treat API keys and OAuth material as secrets; never log or commit them.
