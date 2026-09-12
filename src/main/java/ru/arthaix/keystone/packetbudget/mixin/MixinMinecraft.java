package ru.arthaix.keystone.packetbudget.mixin;

import java.util.Queue;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.keystone.packetbudget.DeferredTiles;
import ru.arthaix.keystone.packetbudget.PacketBudget;

/**
 * runGameLoop (func_71411_J):
 *  - at the head, apply deferred tile-entity tags for up to TE_BUDGET_NANOS;
 *  - the scheduled-task drain `while (!scheduledTasks.isEmpty()) runTask(poll())` gets a
 *    time budget: isEmpty() is redirected to also report "empty" once BUDGET_NANOS of
 *    this frame are spent (at least one task always runs).
 */
@Mixin(value = Minecraft.class, remap = false)
public abstract class MixinMinecraft {
    @Unique private long packetbudget$deadline;
    @Unique private boolean packetbudget$ranOne;

    @Inject(method = "func_71411_J()V", at = @At("HEAD"))
    private void packetbudget$frameStart(CallbackInfo ci) {
        DeferredTiles.process(PacketBudget.TE_BUDGET_NANOS);
        this.packetbudget$deadline = System.nanoTime() + PacketBudget.BUDGET_NANOS;
        this.packetbudget$ranOne = false;
    }

    @Redirect(method = "func_71411_J()V", at = @At(value = "INVOKE", target = "Ljava/util/Queue;isEmpty()Z"))
    private boolean packetbudget$isEmptyOrOverBudget(Queue<?> queue) {
        if (queue.isEmpty()) {
            return true;
        }
        if (this.packetbudget$ranOne && System.nanoTime() > this.packetbudget$deadline) {
            return true;
        }
        this.packetbudget$ranOne = true;
        return false;
    }
}
