package com.zomdroid.renderer;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

public final class PZRendererAgent {
    private PZRendererAgent() {}

    public static void premain(String args, Instrumentation instrumentation) {
        install(instrumentation);
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        install(instrumentation);
    }

    static boolean enabled(String renderer, String compilerFlag) {
        return "MOBILEGLUES_EXPERIMENTAL".equals(renderer) && "1".equals(compilerFlag);
    }

    private static void install(Instrumentation instrumentation) {
        String renderer = System.getProperty("zomdroid.renderer", "");
        String flag = System.getenv("MOBILEGLUES_PZ_WORLD_COMPILER");
        if (!enabled(renderer, flag)) {
            System.out.println("ZOMDROID_PZ_WORLD_COMPILER_V4 enabled=0");
            return;
        }
        try {
            new AgentBuilder.Default()
                    .with(new ByteBuddy().with(TypeValidation.DISABLED))
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override
                        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                                     JavaModule module, boolean loaded, DynamicType dynamicType) {
                            if ("zombie.core.SpriteRenderer".equals(typeDescription.getName())) {
                                System.out.println("ZOMDROID_PZ_WORLD_COMPILER_V4 hook=transformed class=zombie.core.SpriteRenderer");
                            }
                        }

                        @Override
                        public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                                            boolean loaded, Throwable throwable) {
                            if ("zombie.core.SpriteRenderer".equals(typeName)) {
                                System.out.println("ZOMDROID_PZ_WORLD_COMPILER_V4 hook=error reason=" + throwable);
                            }
                        }
                    })
                    .disableClassFormatChanges()
                    .type(ElementMatchers.named("zombie.core.SpriteRenderer"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(WorldPassAdvice.class)
                                    .on(ElementMatchers.named("buildStateDrawBuffer"))))
                    .installOn(instrumentation);
            System.out.println("ZOMDROID_PZ_WORLD_COMPILER_V4 enabled=1 hook=installed version=4 census=world+grammar");
        } catch (Throwable failure) {
            System.out.println("ZOMDROID_PZ_WORLD_COMPILER_V4 enabled=0 hook=error reason=" + failure);
        }
    }
}
