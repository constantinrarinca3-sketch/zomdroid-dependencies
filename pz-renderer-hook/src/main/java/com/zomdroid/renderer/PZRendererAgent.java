package com.zomdroid.renderer;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.named;

/** Standalone, removable hook for the Project Zomboid world renderer. */
public final class PZRendererAgent {
    private PZRendererAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        install(instrumentation);
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        install(instrumentation);
    }

    private static void install(Instrumentation instrumentation) {
        String renderer = System.getProperty("zomdroid.renderer", "");
        boolean enabled = "MOBILEGLUES_EXPERIMENTAL".equals(renderer)
                && "1".equals(System.getenv("MOBILEGLUES_PZ_WORLD_COMPILER"));
        if (!enabled) {
            System.out.println("ZOMDROID_PZ_WORLD_COMPILER enabled=0");
            return;
        }

        try {
            new AgentBuilder.Default()
                    .with(new net.bytebuddy.ByteBuddy().with(TypeValidation.DISABLED))
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override
                        public void onTransformation(TypeDescription typeDescription,
                                                     ClassLoader classLoader,
                                                     JavaModule module,
                                                     boolean loaded,
                                                     DynamicType dynamicType) {
                            System.out.println("ZOMDROID_PZ_WORLD_COMPILER hook=transformed class="
                                    + typeDescription.getName());
                        }

                        @Override
                        public void onError(String typeName,
                                            ClassLoader classLoader,
                                            JavaModule module,
                                            boolean loaded,
                                            Throwable throwable) {
                            System.out.println("ZOMDROID_PZ_WORLD_COMPILER hook=error class="
                                    + typeName + " reason=" + throwable);
                        }
                    })
                    .disableClassFormatChanges()
                    .type(named("zombie.core.SpriteRenderer"))
                    .transform((builder, type, classLoader, module, protectionDomain) -> builder
                            .visit(Advice.to(WorldPassAdvice.class).on(named("buildStateDrawBuffer")))
                            .visit(Advice.to(BuildDrawBufferAdvice.class).on(named("buildDrawBuffer"))))
                    .installOn(instrumentation);
            System.out.println("ZOMDROID_PZ_WORLD_COMPILER enabled=1 hook=installed version=1");
        } catch (Throwable failure) {
            System.out.println("ZOMDROID_PZ_WORLD_COMPILER enabled=0 hook=failed reason=" + failure);
        }
    }
}
