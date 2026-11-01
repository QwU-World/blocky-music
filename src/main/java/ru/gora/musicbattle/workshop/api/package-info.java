/**
 * The Workshop client core without screens: the HTTP client ({@code WorkshopApi}) and its
 * records, sign-in ({@code WorkshopSession}, {@code DeviceFlow}, {@code MinecraftSignIn}), downloads and installed
 * state ({@code WorkshopDownloader}, {@code InstalledCheck}), the inbox ({@code WorkshopInbox}) and the consent file
 * ({@code WorkshopConfig}).
 *
 * <p>Pure JDK and Gson: no Minecraft classes, so {@code tools/WorkshopApiChecks.java} and
 * {@code tools/WorkshopAuthChecks.java} run it against a local fake server. The game wiring (proxy, executors, the
 * Minecraft {@code joinServer} call, toasts, screens) lives in {@code ru.gora.musicbattle.client.workshop}.
 *
 * <p>Rules that hold everywhere here: nothing is sent before network consent; the Minecraft access token never
 * reaches this package; workshop tokens go only to the workshop origin's {@code /api/}; secrets are never logged or
 * printed by {@code toString()}.
 */
package ru.gora.musicbattle.workshop.api;
