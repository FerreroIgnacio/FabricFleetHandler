package net.mapmcbot.mixin;

import java.util.Map;

import net.minecraft.client.render.BlockBreakingInfo;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The blocks being broken as the server shows them, by the breaker's entity id: their cracks. */
@Mixin(WorldRenderer.class)
public interface WorldRendererAccessor {
	@Accessor("blockBreakingInfos")
	Map<Integer, BlockBreakingInfo> mapmcbot$getBlockBreakingInfos();
}
