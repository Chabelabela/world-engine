package io.github.chabelabela.worldengine.nativebridge;

import java.util.Locale;

/** The OS/CPU pair that selects which bundled native library to load. */
public record NativePlatform(String os, String arch) {

    public static NativePlatform current() {
        return detect(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    public static NativePlatform detect(String osName, String osArch) {
        String name = osName.toLowerCase(Locale.ROOT);
        String os;
        if (name.startsWith("windows")) {
            os = "windows";
        } else if (name.startsWith("mac") || name.startsWith("darwin")) {
            os = "macos";
        } else if (name.startsWith("linux")) {
            os = "linux";
        } else {
            throw new NativeCoreException("Unsupported OS: " + osName);
        }
        String arch = switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new NativeCoreException("Unsupported CPU architecture: " + osArch);
        };
        return new NativePlatform(os, arch);
    }

    /** e.g. {@code linux-x86_64}; also the CI artifact and resource folder name. */
    public String key() {
        return os + "-" + arch;
    }

    public String libraryFileName() {
        return switch (os) {
            case "windows" -> "worldengine_core.dll";
            case "macos" -> "libworldengine_core.dylib";
            default -> "libworldengine_core.so";
        };
    }

    /** Classpath location of the bundled library inside the mod jar. */
    public String resourcePath() {
        return "/natives/" + key() + "/" + libraryFileName();
    }
}
