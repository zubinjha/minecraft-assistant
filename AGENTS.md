# AGENTS.md

## Project

This repository contains the Minecraft Assistant project.

## Build and test

- Launch the persistent terminal test interface with `./assistant`.
- Run all automated tests with `./gradlew test`.
- Run environment diagnostics with `./gradlew :cli:run --args=doctor`.
- Run the synthetic live tool test with `./gradlew :cli:run --args=smoke`.
- Run the live Wiki-grounding test with `./gradlew :cli:run --args=wiki-smoke`.
- Java 21 or newer is required to run the build. Production bytecode targets Java 17.

## Working guidelines

- Keep changes focused on the user's request.
- Preserve existing behavior unless a change is explicitly requested.
- Do not commit secrets, credentials, or local environment files.
- Update documentation when setup or usage changes.
- Run relevant checks before considering a change complete.
- Do not create commits or publish the repository unless explicitly requested.
- Keep Minecraft/Fabric code out of the provider, tool transport, and core agent modules.
- Never block the Minecraft render/main thread with model, network, MCP, or authentication work.
- Treat API keys, OAuth material, and Codex credentials as secrets; never log or commit them.
