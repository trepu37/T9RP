package dev.rut9.mixin;

import dev.rut9.ui.ChatT9;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Хуки в ванильный ChatScreen (Yarn, Minecraft 1.21.8). Вся логика — в ChatT9. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
    @Shadow protected TextFieldWidget chatField;

    @Inject(method = "init", at = @At("TAIL"))
    private void rut9$init(CallbackInfo ci) {
        ChatT9.get().onOpen(this.chatField);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void rut9$render(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ChatT9.get().onRender(this.chatField, context, mouseX, mouseY);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void rut9$keyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (ChatT9.get().onKey(this.chatField, keyCode, modifiers)) cir.setReturnValue(true);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void rut9$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (ChatT9.get().onMouse(this.chatField, mouseX, mouseY, button)) cir.setReturnValue(true);
    }
}
