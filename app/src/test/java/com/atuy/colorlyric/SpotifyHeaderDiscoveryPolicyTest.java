/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Iterator;

public final class SpotifyHeaderDiscoveryPolicyTest {
    @Test
    public void recognizesShadedImmutableHeaderContainer() {
        assertTrue(SpotifyHeaderDiscoveryPolicy.isHeaderContainerType(FakeHeaders.class));
        assertFalse(SpotifyHeaderDiscoveryPolicy.isHeaderContainerType(TwoArrayFields.class));
        assertFalse(SpotifyHeaderDiscoveryPolicy.isHeaderContainerType(NoLookup.class));
    }

    @Test
    public void recognizesConcreteAddHeaderOverride() throws Exception {
        Method valid = FakeBuilder.class.getDeclaredMethod(
                "addHeader", String.class, String.class);
        Method wrongType = FakeBuilder.class.getDeclaredMethod(
                "addHeader", String.class, int.class);
        Method staticMethod = FakeBuilder.class.getDeclaredMethod(
                "staticAddHeader", String.class, String.class);
        Method abstractMethod = AbstractBuilder.class.getDeclaredMethod(
                "addHeader", String.class, String.class);

        assertTrue(SpotifyHeaderDiscoveryPolicy.isAddHeaderMethod(valid));
        assertFalse(SpotifyHeaderDiscoveryPolicy.isAddHeaderMethod(wrongType));
        assertFalse(SpotifyHeaderDiscoveryPolicy.isAddHeaderMethod(staticMethod));
        assertFalse(SpotifyHeaderDiscoveryPolicy.isAddHeaderMethod(abstractMethod));
        assertTrue(SpotifyHeaderDiscoveryPolicy.hasAddHeaderMethod(FakeBuilder.class));
    }

    @Test
    public void limitsRuntimeScanningToSpotifyNetworkNamespaces() {
        assertTrue(SpotifyHeaderDiscoveryPolicy.isSpotifyNetworkNamespace("p.ob20"));
        assertTrue(SpotifyHeaderDiscoveryPolicy.isSpotifyNetworkNamespace("com.spotify.net.Client"));
        assertTrue(SpotifyHeaderDiscoveryPolicy.isSpotifyNetworkNamespace("okhttp3.Headers"));
        assertTrue(SpotifyHeaderDiscoveryPolicy.isSpotifyNetworkNamespace(
                "org.chromium.net.impl.CronetUrlRequestBuilderImpl"));
        assertFalse(SpotifyHeaderDiscoveryPolicy.isSpotifyNetworkNamespace("android.app.Activity"));
    }

    private static final class FakeHeaders implements Iterable<String> {
        private final String[] namesAndValues;

        FakeHeaders(String[] namesAndValues) {
            this.namesAndValues = namesAndValues;
        }

        public String get(String name) {
            for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
                if (namesAndValues[i].equalsIgnoreCase(name)) return namesAndValues[i + 1];
            }
            return null;
        }

        @Override
        public Iterator<String> iterator() {
            return Arrays.asList(namesAndValues).iterator();
        }
    }

    private static final class TwoArrayFields implements Iterable<String> {
        private final String[] first;
        private final String[] second;

        TwoArrayFields(String[] values) {
            first = values;
            second = values;
        }

        public String get(String name) {
            return null;
        }

        @Override
        public Iterator<String> iterator() {
            return Arrays.asList(first).iterator();
        }
    }

    private static final class NoLookup implements Iterable<String> {
        private final String[] values;

        NoLookup(String[] values) {
            this.values = values;
        }

        @Override
        public Iterator<String> iterator() {
            return Arrays.asList(values).iterator();
        }
    }

    private static class FakeBuilder {
        FakeBuilder addHeader(String name, String value) {
            return this;
        }

        FakeBuilder addHeader(String name, int value) {
            return this;
        }

        static FakeBuilder staticAddHeader(String name, String value) {
            return new FakeBuilder();
        }
    }

    private abstract static class AbstractBuilder {
        abstract AbstractBuilder addHeader(String name, String value);
    }
}
