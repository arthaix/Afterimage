package ru.arthaix.keystone.chunkkeep.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.arthaix.keystone.chunkkeep.Config;

/**
 * No "moved too quickly" rubber-banding for players in creative or spectator mode.
 *
 * processPlayer() compares each move packet with the position at the start of the tick. When the server or the client
 * stutters, several move packets arrive in one tick and a player flying over the dense city is pulled back although the
 * flight was legal. The check opens with isInvulnerableDimensionChange(); answering true for creative and spectator
 * players skips exactly this check. The second call guards "moved wrongly", which vanilla already skips for those
 * game modes. Survival and adventure players keep the full check.
 */
@Mixin(value = NetHandlerPlayServer.class, remap = false)
public abstract class MixinMovementCheck {
    @Redirect(method = "func_147347_a",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/entity/player/EntityPlayerMP;func_184850_K()Z",
                       ordinal = 0))
    private boolean chunkkeep$skipSpeedCheck(EntityPlayerMP player) {
        if (Config.SKIP_SPEED_CHECK_FOR_BUILDERS && (player.func_184812_l_() || player.func_175149_v())) {
            return true;
        }
        return player.func_184850_K();
    }
}
