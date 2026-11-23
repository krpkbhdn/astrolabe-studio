package com.github.krpkbhdn.astrolabe.ui.state

import com.github.krpkbhdn.astrolabe.ui.await
import com.github.krpkbhdn.astrolabe.update.AvailableUpdate
import com.github.krpkbhdn.astrolabe.update.PreparedUpdate
import com.github.krpkbhdn.astrolabe.update.UpdateOutcome
import com.github.krpkbhdn.astrolabe.update.UpdateService
import java.net.URI
import java.time.Duration
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class UpdateStateFlowTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val offer = AvailableUpdate("1.2.3", "app.deb", 1, URI("https://github.com/o/r/releases/download/v1.2.3/app.deb"), URI("https://github.com/o/r/releases/download/v1.2.3/app.deb.sha256"), URI("https://github.com/o/r/releases/tag/v1.2.3"))
    private val installer: Path = Path.of("updates", "app.deb")
    private val prepared = PreparedUpdate(Path.of("updates", "staged"), installer)
    private val service = mock(UpdateService::class.java).also {
        `when`(it.enabled()).thenReturn(true)
        `when`(it.checkForUpdate()).thenReturn(Optional.of(offer))
        `when`(it.download(any(), any())).thenReturn(installer)
        `when`(it.prepareInstall(installer)).thenReturn(prepared)
    }
    private val errors = CopyOnWriteArrayList<String>()
    private val confirms = CopyOnWriteArrayList<Pair<String, () -> Unit>>()
    private val cancels = CopyOnWriteArrayList<() -> Unit>()
    private val exits = AtomicInteger()
    private val updated = CopyOnWriteArrayList<String>()
    private val state = UpdateState(
        service, scope, { _, message -> errors += message }, { message, install, cancel -> confirms += message to install; cancels += cancel },
        { _, message -> updated += message },
    ) { exits.incrementAndGet() }

    @AfterEach
    fun stop() = scope.cancel()

    private fun ready() {
        state.checkNow()
        await { state.status == UpdateStatus.Available(offer) }
        state.download()
        await { state.status == UpdateStatus.Ready(offer, installer) }
    }

    @Test
    fun `restart shows Preparing while the staging runs off the caller and nothing is asked before it ends`() {
        ready()
        val gate = CountDownLatch(1)
        `when`(service.prepareInstall(installer)).thenAnswer { gate.await(); prepared }

        state.restart()

        await { state.status == UpdateStatus.Preparing(offer) }
        assertThat(confirms).isEmpty()
        gate.countDown()
        await { confirms.isNotEmpty() }
        assertThat(state.status).isEqualTo(UpdateStatus.Ready(offer, installer))
        assertThat(exits.get()).isZero()
    }

    @Test
    fun `a periodic check does not replace the status while preparing`() {
        ready()
        val gate = CountDownLatch(1)
        `when`(service.prepareInstall(installer)).thenAnswer { gate.await(); prepared }
        state.restart()
        await { state.status == UpdateStatus.Preparing(offer) }

        state.checkNow()
        await { !state.checking }

        assertThat(state.status).isEqualTo(UpdateStatus.Preparing(offer))
        gate.countDown()
    }

    @Test
    fun `the confirm names the update and mentions the password only for packages that need it`() {
        `when`(service.needsAuthentication(offer)).thenReturn(true)
        ready()

        state.restart()
        await { confirms.isNotEmpty() }

        assertThat(confirms.single().first).contains("1.2.3").contains("administrator password")
    }

    @Test
    fun `the confirm has no password note when no authentication is needed`() {
        ready()

        state.restart()
        await { confirms.isNotEmpty() }

        assertThat(confirms.single().first).contains("1.2.3").doesNotContain("password")
    }

    @Test
    fun `declining the confirm launches nothing and quits nothing`() {
        ready()

        state.restart()
        await { confirms.isNotEmpty() }

        cancels.single()()

        await { runCatching { verify(service).discard(prepared) }.isSuccess }
        verify(service, never()).launchAfterExit(any())
        assertThat(exits.get()).isZero()
        assertThat(state.status).isEqualTo(UpdateStatus.Ready(offer, installer))
    }

    @Test
    fun `accepting launches the prepared update then exits`() {
        ready()
        state.restart()
        await { confirms.isNotEmpty() }

        confirms.single().second()

        verify(service).launchAfterExit(prepared)
        assertThat(exits.get()).isEqualTo(1)
        assertThat(state.status).isEqualTo(UpdateStatus.Installing(offer))
    }

    @Test
    fun `the start reports a successful earlier update as updated with the running version`() {
        `when`(service.lastOutcome()).thenReturn(Optional.of(UpdateOutcome(UpdateOutcome.Status.OK, "")))
        `when`(service.currentVersion()).thenReturn("1.2.3")
        `when`(service.checkInterval()).thenReturn(Duration.ofHours(1))

        state.start()

        await { updated.isNotEmpty() }
        assertThat(updated.single()).isEqualTo("Updated to version 1.2.3")
        assertThat(errors).isEmpty()
    }

    @Test
    fun `the start reports a failed earlier update as an error naming the logs`() {
        `when`(service.lastOutcome()).thenReturn(Optional.of(UpdateOutcome(UpdateOutcome.Status.FAILED, "install")))
        `when`(service.currentVersion()).thenReturn("1.2.3")
        `when`(service.checkInterval()).thenReturn(Duration.ofHours(1))

        state.start()

        await { errors.isNotEmpty() }
        assertThat(errors.single()).contains("update.log").contains("relaunch.log")
        assertThat(updated).isEmpty()
    }

    @Test
    fun `a failed launch discards the staging, reports and does not exit`() {
        `when`(service.launchAfterExit(any())).thenThrow(IllegalStateException("no pkexec"))
        ready()
        state.restart()
        await { confirms.isNotEmpty() }

        confirms.single().second()

        verify(service).discard(prepared)
        assertThat(state.status).isEqualTo(UpdateStatus.Ready(offer, installer))
        assertThat(errors).containsExactly("no pkexec")
        assertThat(exits.get()).isZero()
    }

    @Test
    fun `a failed preparation returns to Ready and reports the reason without asking`() {
        `when`(service.prepareInstall(installer)).thenThrow(IllegalStateException("The update file changed after it was verified"))
        ready()

        state.restart()

        await { errors.isNotEmpty() }
        assertThat(errors.single()).contains("changed")
        assertThat(state.status).isEqualTo(UpdateStatus.Ready(offer, installer))
        assertThat(confirms).isEmpty()
    }

    @Test
    fun `restart does nothing before the update is downloaded`() {
        state.checkNow()
        await { state.status == UpdateStatus.Available(offer) }

        state.restart()

        verify(service, never()).prepareInstall(any())
        assertThat(state.status).isEqualTo(UpdateStatus.Available(offer))
    }

    @Test
    fun `a failed download returns to Available and reports`() {
        `when`(service.download(any(), any())).thenThrow(IllegalStateException("The update signature could not be verified: no match"))
        state.checkNow()
        await { state.status == UpdateStatus.Available(offer) }

        state.download()

        await { errors.isNotEmpty() }
        assertThat(state.status).isEqualTo(UpdateStatus.Available(offer))
    }

    @Test
    fun `an unsupported or unsigned offer only offers the release page`() {
        `when`(service.canInstall(offer)).thenReturn(false)
        state.checkNow()
        await { state.status == UpdateStatus.Available(offer) }

        assertThat(state.actionFor(offer)).isEqualTo(UpdateAction("Open release page", false, null))
    }

    @Test
    fun `the install action is resolved by the check and composition never asks the backend`() {
        `when`(service.canInstall(offer)).thenReturn(true)
        `when`(service.needsAuthentication(offer)).thenReturn(true)

        assertThat(state.actionFor(offer)).isEqualTo(UpdateAction("Open release page", false, null))
        verify(service, never()).canInstall(any())
        verify(service, never()).needsAuthentication(any())

        state.checkNow()
        await { state.status == UpdateStatus.Available(offer) }

        assertThat(state.actionFor(offer)).isEqualTo(UpdateAction("Install and restart", true, "Asks for your administrator password"))
        val asked = org.mockito.Mockito.mockingDetails(service).invocations.size
        state.actionFor(offer)
        assertThat(org.mockito.Mockito.mockingDetails(service).invocations).hasSize(asked)
    }

    @Test
    fun `a check that returns after a download started does not overwrite the download`() {
        state.checkNow()
        await { state.status == UpdateStatus.Available(offer) }
        val gate = CountDownLatch(1)
        `when`(service.checkForUpdate()).thenAnswer { gate.await(); Optional.of(offer) }
        await { !state.checking }
        state.checkNow()
        await { state.checking }

        state.download()
        await { state.status == UpdateStatus.Ready(offer, installer) }
        gate.countDown()
        await { !state.checking }

        assertThat(state.status).isEqualTo(UpdateStatus.Ready(offer, installer))
    }
}
