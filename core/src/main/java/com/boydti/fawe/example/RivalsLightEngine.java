package com.boydti.fawe.example;

import com.boydti.fawe.Fawe;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

// Reflection hook into spigot light engine
final class RivalsLightEngine {
    private static final Method RELIGHT_CHUNK = findRelightChunk();

    private RivalsLightEngine() {
    }

    static boolean isAvailable() {
        return RELIGHT_CHUNK != null;
    }

    static boolean relightChunk(NMSMappedFaweQueue queue, int x, int z) {
        if (RELIGHT_CHUNK == null) {
            return false;
        }
        try {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    queue.ensureChunkLoaded(x + dx, z + dz);
                }
            }

            Object chunk = queue.ensureChunkLoaded(x, z);
            if (chunk == null) {
                return false;
            }
            Object world = getChunkWorld(chunk);
            if (world == null) {
                return false;
            }

            Object result = RELIGHT_CHUNK.invoke(null, world, chunk);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method findRelightChunk() {
        try {
            Class<?> lightEngine = Class.forName("net.pvprivals.spigot.lighting.LightEngine", false, RivalsLightEngine.class.getClassLoader());
            for (Method method : lightEngine.getMethods()) {
                if (!"relightChunk".equals(method.getName()) || !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2 && method.getReturnType() == Boolean.TYPE) {
                    Fawe.debug("FAWE found custom spigot LightEngine; chunk relighting will run sync and use spigot hook when possible.");
                    return method;
                }
            }
        } catch (Throwable ignored) {
        }
        Fawe.debug("FAWE did not find custom spigot LightEngine; using FAWE relighting.");
        return null;
    }

    private static Object getChunkWorld(Object chunk) throws Exception {
        try {
            Method getWorld = chunk.getClass().getMethod("getWorld");
            return getWorld.invoke(chunk);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Field world = chunk.getClass().getField("world");
            return world.get(chunk);
        } catch (NoSuchFieldException ignored) {
        }
        Field world = chunk.getClass().getDeclaredField("world");
        world.setAccessible(true);
        return world.get(chunk);
    }
}
