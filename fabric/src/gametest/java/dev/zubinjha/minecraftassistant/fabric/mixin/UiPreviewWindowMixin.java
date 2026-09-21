package dev.zubinjha.minecraftassistant.fabric.mixin;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
abstract class UiPreviewWindowMixin {
    private static final String PREVIEW_ENV = "MINECRAFT_ASSISTANT_UI_PREVIEW";

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwShowWindow(J)V")
    )
    private void minecraftAssistant$showWindow(long window) {
        if (!Boolean.parseBoolean(System.getenv(PREVIEW_ENV))) {
            GLFW.glfwShowWindow(window);
        }
    }
}
