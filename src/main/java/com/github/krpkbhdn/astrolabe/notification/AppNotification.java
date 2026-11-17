package com.github.krpkbhdn.astrolabe.notification;

import java.time.Instant;

public record AppNotification(long id, Instant at, NotificationKind kind, long projectId, String astrolabeSessionId, String title, String body) {}
