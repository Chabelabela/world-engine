package io.github.chabelabela.worldengine.nativebridge;

/** Any failure loading or calling the Rust core. */
public final class NativeCoreException extends RuntimeException {
    public NativeCoreException(String message) {
        super(message);
    }

    public NativeCoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
