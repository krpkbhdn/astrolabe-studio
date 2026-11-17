package com.github.krpkbhdn.astrolabe.claude.hook;

import com.github.krpkbhdn.astrolabe.claude.event.HookEvent;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** What the hook server needs of whoever settles held hook calls (implemented by the proposal service). */
public interface DecisionHolder {

    /**
     * Settles [event]; [publish] announces it to the app and is run by the holder, except when the event is answered at once without the user.
     * @return the hook output once settled, empty when the terminal dialog stays in charge
     */
    CompletableFuture<Optional<String>> hold(HookEvent event, Runnable publish);

    default CompletableFuture<Optional<String>> hold(HookEvent event) {
        return hold(event, () -> {});
    }

    void releaseAll();
}
