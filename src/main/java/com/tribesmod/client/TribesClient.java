package com.tribesmod.client;

import com.tribesmod.TribesMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class TribesClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(TribesMod.TRIBESMAN, TribesmanRenderer::new);
    }
}
