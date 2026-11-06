package ru.aw.launcher.packaging;

/** Required only to generate a jpackage image. Packaging replaces the launcher with Rust. */
public final class JPackagePlaceholder {
    public static void main(String[] args) {
        throw new UnsupportedOperationException("Launch the packaged Rust executable");
    }
}
