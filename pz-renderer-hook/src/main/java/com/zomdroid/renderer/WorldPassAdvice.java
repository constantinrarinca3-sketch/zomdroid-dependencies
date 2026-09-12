package com.zomdroid.renderer;

import net.bytebuddy.asm.Advice;
import zombie.core.PZWorldCompiler;

public final class WorldPassAdvice {
    private WorldPassAdvice() {
    }

    @Advice.OnMethodEnter
    public static boolean enter() {
        return PZWorldCompiler.enterWorldPass();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter boolean previous) {
        PZWorldCompiler.leaveWorldPass(previous);
    }
}
