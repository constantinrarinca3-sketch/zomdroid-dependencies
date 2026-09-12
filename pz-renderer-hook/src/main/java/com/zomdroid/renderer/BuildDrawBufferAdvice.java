package com.zomdroid.renderer;

import net.bytebuddy.asm.Advice;
import zombie.core.PZWorldCompiler;
import zombie.core.Styles.Style;
import zombie.core.textures.TextureDraw;

public final class BuildDrawBufferAdvice {
    private BuildDrawBufferAdvice() {
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter(@Advice.Argument(0) TextureDraw[] draws,
                                @Advice.Argument(1) Style[] styles,
                                @Advice.Argument(2) int count) {
        return PZWorldCompiler.compile(draws, styles, count);
    }
}
