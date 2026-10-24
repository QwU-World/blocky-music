/**
 * Workshop Beatmaps, pure part: the downloadable beatmap package ({@code .blockymap}), its manifest, the validating
 * atomic installer, the client's catalog of installed sets ({@link ru.gora.musicbattle.workshop.WorkshopCatalog}) and
 * the {@code bmw-join-v1} hash ({@link ru.gora.musicbattle.workshop.ServerIdHash}).
 *
 * <p>Pure {@code java.nio}, {@code java.util.zip} and Gson, with no Minecraft classes, so the checks run it without the
 * game ({@code python3 tools/run-check.py workshop-package}, {@code workshop-api}, {@code workshop-auth}). The client
 * core that talks to the workshop lives in {@code workshop.api}; the game wiring (keys, screens, drops, inbox) in
 * {@code client.workshop}. Installed sets are client-only state and are never loaded into {@code MusicBattleTracks}
 * ({@code workshop-catalog-isolation}).
 *
 * <p>The design, the package format and the phases are in {@code docs/WORKSHOP.md}; the binding spec is the
 * blocky-music-workshop repository, {@code docs/spec/06-mod.md} with {@code 08-amendments.md}.
 */
package ru.gora.musicbattle.workshop;
