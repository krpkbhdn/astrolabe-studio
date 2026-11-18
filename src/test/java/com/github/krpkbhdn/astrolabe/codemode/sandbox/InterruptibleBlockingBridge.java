package com.github.krpkbhdn.astrolabe.codemode.sandbox;

import com.github.krpkbhdn.astrolabe.codemode.HostBridge;
import java.util.concurrent.CountDownLatch;

/** A host bridge whose tool call blocks until its thread is interrupted, which {@code interrupted} then reports. */
final class InterruptibleBlockingBridge implements HostBridge {

    final CountDownLatch interrupted = new CountDownLatch(1);

    @Override
    public String callTool(String server, String tool, String argsJson) {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            interrupted.countDown();
        }
        return "{}";
    }

    @Override
    public void log(String line) {
    }
}
