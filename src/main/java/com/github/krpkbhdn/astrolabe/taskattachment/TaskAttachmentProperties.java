package com.github.krpkbhdn.astrolabe.taskattachment;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.positive;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits of what is pasted into the Start Task dialog: images per task, the largest encoded PNG, how long unused image files are kept,
 * and when pasted text is long enough (more lines or more characters than these) to be shown as a placeholder instead of inline.
 */
@ConfigurationProperties("astrolabe.task-attachments")
public record TaskAttachmentProperties(@DefaultValue("10") int maxImages, @DefaultValue("20971520") long maxImageBytes,
        @DefaultValue("7d") Duration retention, @DefaultValue("10") int longPasteLines, @DefaultValue("1000") int longPasteChars) {

    public TaskAttachmentProperties {
        positive("astrolabe.task-attachments.max-images", maxImages);
        positive("astrolabe.task-attachments.max-image-bytes", maxImageBytes);
        positive("astrolabe.task-attachments.retention", retention);
        positive("astrolabe.task-attachments.long-paste-lines", longPasteLines);
        positive("astrolabe.task-attachments.long-paste-chars", longPasteChars);
    }
}
