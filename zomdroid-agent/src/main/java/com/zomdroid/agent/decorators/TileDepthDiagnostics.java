package com.zomdroid.agent.decorators;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded MobileGlues diagnostic for the two Build 42 tile-depth programs.
 *
 * Project Zomboid normally reports compiler/linker errors only through its optional
 * Shader log category, then clears the failed shader from SceneShaderStore. The later
 * renderer error only says that the field is null. This advice records the original
 * driver compiler output once, while the shader object still exists.
 */
public final class TileDepthDiagnostics {
    private static final int GL_COMPILE_STATUS = 0x8B81;
    private static final int GL_INFO_LOG_LENGTH = 0x8B84;
    private static final Set<String> printedUnits = ConcurrentHashMap.newKeySet();
    private static final Set<String> printedPrograms = ConcurrentHashMap.newKeySet();

    private TileDepthDiagnostics() {
    }

    private static boolean isTileDepthProgram(String name) {
        return "tileWithDepth".equals(name)
                || "opaqueWithDepth".equals(name)
                || "seamFix2".equals(name)
                || "CutawayAttached".equals(name);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] types, Object... args)
            throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName, types);
        return method.invoke(target, args);
    }

    private static int asInt(Object value) {
        return value instanceof Integer ? (Integer) value : 0;
    }

    private static String trimLog(String log) {
        if (log == null) return "<no driver log>";
        final int limit = 4096;
        return log.length() <= limit ? log : log.substring(0, limit) + "\n[truncated]";
    }

    private static void printUnitResult(Object shaderUnit) {
        try {
            String fileName = (String) invoke(shaderUnit, "getFileName", new Class<?>[0]);
            if (fileName == null || (!fileName.contains("tileWithDepth")
                    && !fileName.contains("opaqueWithDepth")
                    && !fileName.contains("seamFix2")
                    && !fileName.contains("CutawayAttached"))) {
                return;
            }

            int shaderId = asInt(invoke(shaderUnit, "getGLID", new Class<?>[0]));
            String key = fileName + "#" + shaderId;
            if (!printedUnits.add(key)) return;

            ClassLoader loader = shaderUnit.getClass().getClassLoader();
            Class<?> gl20 = Class.forName("org.lwjgl.opengl.GL20", false, loader);
            Method getShaderi = gl20.getMethod("glGetShaderi", int.class, int.class);
            Method getShaderInfoLog = gl20.getMethod("glGetShaderInfoLog", int.class, int.class);
            int compiled = (Integer) getShaderi.invoke(null, shaderId, GL_COMPILE_STATUS);
            int logLength = (Integer) getShaderi.invoke(null, shaderId, GL_INFO_LOG_LENGTH);
            String infoLog = (String) getShaderInfoLog.invoke(null, shaderId, Math.max(logLength, 1));

            System.out.println("[zomdroid tiledepth] unit=" + fileName
                    + " id=" + shaderId + " compile=" + compiled
                    + " log:\n" + trimLog(infoLog));
        } catch (Throwable ignored) {
            // A diagnostic must never alter the game renderer's failure behaviour.
        }
    }

    private static void printProgramResult(Object shaderProgram) {
        try {
            String name = (String) invoke(shaderProgram, "getName", new Class<?>[0]);
            if (!isTileDepthProgram(name) || !printedPrograms.add(name)) return;

            Field failedField = shaderProgram.getClass().getDeclaredField("compileFailed");
            failedField.setAccessible(true);
            boolean failed = failedField.getBoolean(shaderProgram);
            // Do not call getShaderID(): on a failed program PZ treats it as a request to
            // compile again, which would make a passive diagnostic re-enter compilation.
            Field shaderIdField = shaderProgram.getClass().getDeclaredField("shaderId");
            shaderIdField.setAccessible(true);
            int shaderId = shaderIdField.getInt(shaderProgram);
            System.out.println("[zomdroid tiledepth] program=" + name
                    + " compileFailed=" + failed + " id=" + shaderId);
        } catch (Throwable ignored) {
            // See printUnitResult: this path must remain observational only.
        }
    }

    public static class ShaderUnitCompile {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.This Object shaderUnit) {
            printUnitResult(shaderUnit);
        }
    }

    public static class ShaderProgramCompile {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.This Object shaderProgram) {
            printProgramResult(shaderProgram);
        }
    }
}
