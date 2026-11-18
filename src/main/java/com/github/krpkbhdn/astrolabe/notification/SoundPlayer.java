package com.github.krpkbhdn.astrolabe.notification;

/** Plays a sound without blocking the caller and without ever throwing. */
public interface SoundPlayer {
    void play(NotificationSound sound, double volume);
}
