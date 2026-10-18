package com.tribesmod.client;

import com.tribesmod.TribesmanEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

public class TribesmanRenderer extends HumanoidMobRenderer<TribesmanEntity, HumanoidModel<TribesmanEntity>> {
    private static final String[] SKINS = {"steve", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};

    public TribesmanRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
    }

    @Override
    public ResourceLocation getTextureLocation(TribesmanEntity e) {
        // each role looks different inside a tribe
        String skin = SKINS[Math.floorMod(e.getTribeId() * 3 + e.getRole(), SKINS.length)];
        return ResourceLocation.withDefaultNamespace("textures/entity/player/wide/" + skin + ".png");
    }
}
