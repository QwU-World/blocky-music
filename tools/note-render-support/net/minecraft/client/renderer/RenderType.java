package net.minecraft.client.renderer;

/** Test-only GPU batch boundary. The real RenderType static initializer requires game registries. */
public final class RenderType {
    private static final RenderType GUI = new RenderType();
    public static RenderType gui() { return GUI; }
}
