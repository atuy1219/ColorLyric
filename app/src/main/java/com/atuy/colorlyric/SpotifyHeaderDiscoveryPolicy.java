/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class SpotifyHeaderDiscoveryPolicy {
    private SpotifyHeaderDiscoveryPolicy() {}

    static boolean isHeaderContainerType(Class<?> type) {
        if (type == null
                || type.isInterface()
                || Modifier.isAbstract(type.getModifiers())
                || !Iterable.class.isAssignableFrom(type)) {
            return false;
        }

        int stringArrayFields = 0;
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == String[].class) {
                stringArrayFields++;
            }
        }
        if (stringArrayFields != 1) return false;

        boolean hasIterator = false;
        boolean hasStringLookup = false;
        for (Method method : type.getDeclaredMethods()) {
            if ("iterator".equals(method.getName()) && method.getParameterCount() == 0) {
                hasIterator = true;
            }
            if (!Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == String.class
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == String.class) {
                hasStringLookup = true;
            }
        }
        if (!hasIterator || !hasStringLookup) return false;

        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 1
                    && constructor.getParameterTypes()[0] == String[].class) {
                return true;
            }
        }
        return false;
    }

    static boolean isAddHeaderMethod(Method method) {
        if (method == null
                || Modifier.isStatic(method.getModifiers())
                || Modifier.isAbstract(method.getModifiers())
                || !"addHeader".equals(method.getName())
                || method.getParameterCount() != 2) {
            return false;
        }
        Class<?>[] parameters = method.getParameterTypes();
        return parameters[0] == String.class && parameters[1] == String.class;
    }

    static boolean hasAddHeaderMethod(Class<?> type) {
        if (type == null || type.isInterface()) return false;
        for (Method method : type.getDeclaredMethods()) {
            if (isAddHeaderMethod(method)) return true;
        }
        return false;
    }

    static boolean isSpotifyNetworkNamespace(String className) {
        if (className == null) return false;
        return className.startsWith("p.")
                || className.startsWith("com.spotify.")
                || className.startsWith("okhttp3.")
                || className.startsWith("org.chromium.net.");
    }
}
