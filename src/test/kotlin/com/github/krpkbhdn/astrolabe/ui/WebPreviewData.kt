package com.github.krpkbhdn.astrolabe.ui

import com.github.krpkbhdn.astrolabe.config.AppProperties
import com.github.krpkbhdn.astrolabe.config.DatabaseConfiguration
import com.github.krpkbhdn.astrolabe.config.DatabaseProperties
import com.github.krpkbhdn.astrolabe.workspace.TreeDeleter
import java.time.Duration
import com.github.krpkbhdn.astrolabe.web.ElementAnchor
import com.github.krpkbhdn.astrolabe.web.StyleChange
import com.github.krpkbhdn.astrolabe.web.WebFeedbackWriter
import com.github.krpkbhdn.astrolabe.web.WebReviewProperties
import com.github.krpkbhdn.astrolabe.web.WebReviewService
import com.github.krpkbhdn.astrolabe.web.WebScreenshotStore
import java.nio.file.Files
import java.nio.file.Path
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

private const val OUTER_HTML_LIMIT = 600
private const val MAX_SCREENSHOT_BYTES = 5_242_880L
private const val MAX_ITEMS = 50

/** A real [WebReviewService] on a throwaway SQLite file, plus the revision the `--web` preview flag opens over the bundled demo page. */
internal object WebPreviewData {
    @Volatile
    lateinit var current: WebReviewService
        private set
    private lateinit var jdbc: JdbcClient

    /** Throwaway data dir of the preview: the database, screenshots and the JCEF cache and log, so the user's real ~/.astrolabe-studio stays untouched. */
    val dataDir: Path by lazy {
        Files.createTempDirectory("astrolabe-preview-web").also { dir ->
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { TreeDeleter.delete(dir) } })
        }
    }

    fun service(): WebReviewService {
        val dir = dataDir
        val dataSource = DatabaseConfiguration.create(dir.resolve("web.db"), DatabaseProperties(Duration.ofSeconds(5), 4))
        Flyway.configure().dataSource(dataSource).load().migrate()
        jdbc = JdbcClient.create(dataSource)
        val properties = WebReviewProperties(OUTER_HTML_LIMIT, listOf("display", "color"), MAX_SCREENSHOT_BYTES, MAX_ITEMS)
        return WebReviewService(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)), properties, WebScreenshotStore(AppProperties(dir), properties), WebFeedbackWriter(properties))
            .also { current = it }
    }

    /** One revision of two items on the demo page, so the Web tab opens on a page that already has pins. */
    fun seed(projectId: Long) {
        jdbc.sql("INSERT OR IGNORE INTO project (id, name, path, created_at) VALUES (?, 'astrolabe', 'preview', 'now')").param(projectId).update()
        val url = previewFixtureUrl()
        add(projectId, url, "#hero h1", "h1", ElementAnchor.Rect(40.0, 90.0, 300.0, 40.0), "The hero heading is too small.", StyleChange("font-size", "52px", "60px"))
        add(projectId, url, ".card:nth-child(1) .btn", "button", ElementAnchor.Rect(90.0, 640.0, 110.0, 38.0), "Learn more should be the primary colour.")
    }

    private fun add(projectId: Long, url: String, selector: String, tag: String, rect: ElementAnchor.Rect, comment: String, vararg changes: StyleChange) {
        val anchor = ElementAnchor(selector, "/html/body/$tag", tag, ElementAnchor.SourceHint("Demo", "fixture.html", 1), rect, "", "", "<$tag>", mapOf("color" to "black"))
        current.addItem(projectId, url, "Web review demo", anchor, comment, changes.toList())
    }
}
