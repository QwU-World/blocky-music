package ru.gora.musicbattle.workshop.api;

/**
 * At most one automatic Minecraft proof per game launch (Mojang allows 6
 * joins per 30 s and suspends accounts that send many failing requests), and only for a Minecraft account that has
 * signed in to the Workshop on this computer before (owner decision 01.11.2026; see {@link MinecraftSignIn.Memory}).
 * The attempt is only spent when it really starts: a call while a server connection is logging in, without consent,
 * for a non-Microsoft account or for an account this computer does not remember leaves it available for later.
 */
public final class AutoSignInGate {
    /** Why an automatic attempt did not start (or {@link #STARTED}). */
    public enum Decision {
        STARTED, ALREADY_ATTEMPTED, NO_CONSENT, NOT_MICROSOFT_ACCOUNT, LOGIN_IN_PROGRESS, ALREADY_SIGNED_IN, NOT_REMEMBERED
    }

    private boolean attempted;

    /**
     * {@code remembered}: this Minecraft account signed in here with its Minecraft proof and has not signed out since.
     * Without it nothing is sent: no challenge, no join, no verify.
     */
    public synchronized Decision tryStart(boolean consent, boolean microsoftAccount, boolean loginInProgress, boolean signedIn,
                                          boolean remembered) {
        if (attempted) {
            return Decision.ALREADY_ATTEMPTED;
        }
        if (!consent) {
            return Decision.NO_CONSENT;
        }
        if (signedIn) {
            return Decision.ALREADY_SIGNED_IN;
        }
        if (!microsoftAccount) {
            return Decision.NOT_MICROSOFT_ACCOUNT;
        }
        if (!remembered) {
            return Decision.NOT_REMEMBERED;
        }
        if (loginInProgress) {
            return Decision.LOGIN_IN_PROGRESS;
        }
        attempted = true;
        return Decision.STARTED;
    }

    public synchronized boolean attempted() {
        return attempted;
    }
}
