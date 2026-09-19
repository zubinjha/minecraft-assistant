# Installation

Minecraft Assistant currently targets Minecraft 26.2 and runs entirely on the client. Servers and Realms do not install anything, and `/ask` is handled locally.

This is an unreleased development build. GitHub Releases and Modrinth will become the normal download locations after the first alpha is ready.

## Prism Launcher

1. Select an existing Minecraft 26.2 instance and choose **Edit**.
2. On **Version**, choose **Install Loader**, select **Fabric**, and install Fabric Loader 0.19.5 or a newer compatible stable release.
3. On **Mods**, choose **Download Mods**, find **Fabric API**, and install version 0.160.0+26.2 or a newer compatible 26.2 release.
4. Still on **Mods**, choose **Add File** and select the Minecraft Assistant JAR.
5. Launch the instance and enter a local world.
6. Run `/ask config`, enter an OpenRouter API key, keep the recommended model initially, and use **Test Connection**.
7. Save, then try `/ask how do I craft a recovery compass?`, `/ask how do I smelt glass?`, or `/ask how do I go from sand to glass panes?`. Single operations use **Show Recipe**; connected production chains use one **Show N Steps** action.

The development JAR is produced at:

```text
fabric/build/libs/minecraft-assistant-0.1.0-SNAPSHOT.jar
```

## Standard Minecraft Launcher

1. Install the Fabric Loader profile for Minecraft 26.2 from Fabric's official installer.
2. Launch that profile once, then close Minecraft.
3. Put the compatible Fabric API JAR and Minecraft Assistant JAR in the profile's `mods` directory.
4. Launch the Fabric profile, enter a local world, and run `/ask config`.

Prism Launcher is the primary tested path for the current development build.

## In-game commands

```text
/ask <question>  Ask a question
/ask config      Open provider settings
/ask stop        Cancel the active request
/ask clear       Clear recent conversation memory
```

`/mcai ask ...` is retained as a fallback if a server defines a conflicting `/ask` command.

## Configuration and privacy

- The OpenRouter key is masked after entry.
- Non-secret settings are stored in `config/minecraft-assistant.json` inside the Minecraft instance.
- The key is stored separately in `config/minecraft-assistant-credentials.json` and restricted to the current operating-system user where supported.
- The key is sent only to OpenRouter.
- Wiki search arguments are sent to the configured Minecraft Wiki MCP service without the provider key.
- Credentials are never intentionally written to Minecraft chat or normal project logs.

Local credential files are convenient but are not equivalent to an operating-system credential vault. OpenRouter browser authentication and stronger cross-platform credential storage remain release-hardening work.

## Troubleshooting

- **`/ask` is unknown:** confirm Fabric Loader, Fabric API, and the Minecraft Assistant mod are enabled for the same 26.2 instance.
- **OpenRouter rejected the API key:** reopen `/ask config`, replace the key, and run **Test Connection**.
- **The selected model is unavailable:** restore `openai/gpt-5-mini` or choose another OpenRouter model that supports tools.
- **The Wiki is unavailable:** retry later; a failed retrieval should not crash or freeze Minecraft.
- **A server owns `/ask`:** use `/mcai ask <question>`.
