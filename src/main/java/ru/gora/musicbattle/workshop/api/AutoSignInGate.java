package ru.gora.musicbattle.workshop.api;

/**
 * At most one automatic Minecraft proof per game launch (spec 06 section 7.2, research section 1.6: Mojang allows 6
 * joins per 30 s and suspends accounts that send many failing requests). The attempt is only spent when it really
 * starts: a call while a server connection is logging in, without consent, or for a non-Microsoft account leaves it
 * available for later.
 */
public final class AutoSignInGate {
    /** Why an automatic attempt did not start (or {@link #STARTED}). */
    public enum Decision {
        STARTED, ALREADY_ATTEMPTED, NO_CONSENT, NOT_MICROSOFT_ACCOUNT, LOGIN_IN_PROGRESS, ALREADY_SIGNED_IN
    }

    private boolean attempted;

    public synchronized Decision tryStart(boolean consent, boolean microsoftAccount, boolean loginInProgress, boolean signedIn) {
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
