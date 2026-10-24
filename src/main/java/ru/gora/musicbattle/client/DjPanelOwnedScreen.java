package ru.gora.musicbattle.client;

/** Marks nested DJ editors whose owning panel must keep receiving live server state. */
interface DjPanelOwnedScreen {
    DjPanelScreen owningDjPanel();
}
