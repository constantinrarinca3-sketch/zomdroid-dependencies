package com.zomdroid.renderer;

import net.bytebuddy.asm.Advice;
import zombie.core.PZWorldCompiler;
import zombie.core.WorldDrawCensus;
import zombie.core.sprite.SpriteRenderState;

public final class WorldPassAdvice {
    private WorldPassAdvice() {}

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter(@Advice.Argument(0) SpriteRenderState state) {
        WorldDrawCensus.observe(state);
        return PZWorldCompiler.compileWorldState(state);
    }
}
