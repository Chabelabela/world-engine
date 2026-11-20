package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NativePlatformTest {
    @Test
    void mapsLinuxAmd64() {
        NativePlatform p = NativePlatform.detect("Linux", "amd64");
        assertEquals("linux-x86_64", p.key());
        assertEquals("libworldengine_core.so", p.libraryFileName());
    }

    @Test
    void mapsWindows() {
        assertEquals("worldengine_core.dll", NativePlatform.detect("Windows 11", "amd64").libraryFileName());
    }

    @Test
    void mapsAppleSilicon() {
        assertEquals("/natives/macos-aarch64/libworldengine_core.dylib",
                NativePlatform.detect("Mac OS X", "aarch64").resourcePath());
    }

    @Test
    void rejectsUnsupportedPlatforms() {
        assertThrows(NativeCoreException.class, () -> NativePlatform.detect("SunOS", "sparcv9"));
    }
}
