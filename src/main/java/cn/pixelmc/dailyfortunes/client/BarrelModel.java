package cn.pixelmc.dailyfortunes.client;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;
public record BarrelModel(BakedModel delegate) implements BakedModel {
 public List<BakedQuad> getQuads(BlockState state,Direction side,RandomSource random){return delegate.getQuads(state,side,random);}
 public boolean useAmbientOcclusion(){return delegate.useAmbientOcclusion();}
 public boolean isGui3d(){return delegate.isGui3d();}
 public boolean usesBlockLight(){return false;}
 public boolean isCustomRenderer(){return true;}
 public TextureAtlasSprite getParticleIcon(){return delegate.getParticleIcon();}
 public ItemTransforms getTransforms(){return delegate.getTransforms();}
 public ItemOverrides getOverrides(){return delegate.getOverrides();}
}
