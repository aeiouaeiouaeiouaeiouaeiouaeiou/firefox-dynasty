/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.media.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineExceptionHandler
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.MediaSessionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.concept.engine.mediasession.MediaSession.Metadata
import mozilla.components.concept.engine.mediasession.MediaSession.PlaybackState
import mozilla.components.feature.media.ext.toPlaybackState
import mozilla.components.feature.media.facts.MediaFacts
import mozilla.components.feature.media.notification.MediaNotification
import mozilla.components.feature.media.session.MediaSessionCallback
import mozilla.components.support.base.Component
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.base.facts.Action
import mozilla.components.support.base.facts.processor.CollectionProcessor
import mozilla.components.support.base.ids.SharedIdsHelper
import mozilla.components.support.test.any
import mozilla.components.support.test.argumentCaptor
import mozilla.components.support.test.coMock
import mozilla.components.support.test.eq
import mozilla.components.support.test.mock
import mozilla.components.support.test.robolectric.testContext
import mozilla.components.support.test.rule.MainCoroutineRule
import mozilla.components.support.test.rule.runTestOnMain
import mozilla.components.support.test.whenever
import mozilla.components.support.utils.ext.stopForegroundCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.robolectric.util.ReflectionHelpers.setStaticField
import kotlin.reflect.jvm.javaField
import android.media.session.PlaybackState as AndroidPlaybackState

@RunWith(AndroidJUnit4::class)
class MediaSessionServiceDelegateTest {

    @get:Rule
    val coroutinesTestRule = MainCoroutineRule()

    private val notificationId = SharedIdsHelper.getIdForTag(testContext, AbstractMediaSessionService.NOTIFICATION_TAG)

    @Test
    fun `WHEN the service is created THEN create a new notification scope audio focus manager`() {
        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), mock())
        delegate.mediaSession = mock()
        val mediaCallbackCaptor = argumentCaptor<MediaSessionCallback>()

        delegate.onCreate()

        verify(delegate.mediaSession).setCallback(mediaCallbackCaptor.capture())
        assertNotNull(delegate.notificationScope)
    }

    @Test
    fun `WHEN the service is destroyed THEN stop notification updates and abandon audio focus`() {
        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), mock())
        delegate.audioFocus = mock()

        delegate.onDestroy()

        verify(delegate.audioFocus)!!.abandon()
        verify(delegate.service, never()).stopSelf()
        assertNull(delegate.notificationScope)
    }

    @Test
    fun `GIVEN media playing started WHEN a new play command is received THEN resume media and emit telemetry`() {
        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), mock())
        delegate.controller = mock() // simulate media already started playing

        CollectionProcessor.withFactCollection { facts ->
            delegate.onStartCommand(Intent(AbstractMediaSessionService.ACTION_PLAY))

            verify(delegate.controller)!!.play()
            assertEquals(1, facts.size)
            with(facts[0]) {
                assertEquals(Component.FEATURE_MEDIA, component)
                assertEquals(Action.PLAY, action)
                assertEquals(MediaFacts.Items.NOTIFICATION, item)
            }
        }
    }

    @Test
    fun `GIVEN media playing started WHEN a new pause command is received THEN pause media and emit telemetry`() {
        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), mock())
        delegate.controller = mock() // simulate media already started playing

        CollectionProcessor.withFactCollection { facts ->
            delegate.onStartCommand(Intent(AbstractMediaSessionService.ACTION_PAUSE))

            verify(delegate.controller)!!.pause()
            assertEquals(1, facts.size)
            with(facts[0]) {
                assertEquals(Component.FEATURE_MEDIA, component)
                assertEquals(Action.PAUSE, action)
                assertEquals(MediaFacts.Items.NOTIFICATION, item)
            }
        }
    }

    @Test
    fun `WHEN the task is removed THEN stop media in all tabs and shutdown`() {
        val notificationManagerCompat: NotificationManagerCompat = mock()
        val notificationsDelegate: NotificationsDelegate = mock()
        whenever(notificationsDelegate.notificationManagerCompat).thenReturn(notificationManagerCompat)

        val mediaTab1 = getMediaTab()
        val mediaTab2 = getMediaTab(PlaybackState.PAUSED)
        val store = BrowserStore(
            BrowserState(
                tabs = listOf(mediaTab1, mediaTab2),
            ),
        )
        val delegate = MediaSessionServiceDelegate(testContext, mock(), store, mock(), notificationsDelegate)
        delegate.mediaSession = mock()

        delegate.onTaskRemoved()

        verify(mediaTab1.mediaSessionState!!.controller).stop()
        verify(mediaTab2.mediaSessionState!!.controller).stop()
        verify(delegate.mediaSession).release()
        verify(delegate.service).stopSelf()
    }

    @Test
    fun `WHEN handling playing media THEN emit telemetry`() {
        val mediaTab = getMediaTab()
        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), mock())
        delegate.audioFocus = mock()

        CollectionProcessor.withFactCollection { facts ->
            delegate.handleMediaPlaying(mediaTab)

            assertEquals(1, facts.size)
            with(facts[0]) {
                assertEquals(Component.FEATURE_MEDIA, component)
                assertEquals(Action.PLAY, action)
                assertEquals(MediaFacts.Items.STATE, item)
            }
        }
    }

    @Test
    fun `WHEN handling playing media THEN setup internal properties`() {
        val mediaTab = getMediaTab()
        val delegate = spy(MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), mock()))
        delegate.audioFocus = mock()

        delegate.handleMediaPlaying(mediaTab)

        verify(delegate).updateMediaSession(mediaTab)
        verify(delegate).registerBecomingNoisyListenerIfNeeded(mediaTab)
        assertSame(mediaTab.mediaSessionState!!.controller, delegate.controller)
    }

    @Test
    fun `GIVEN the service is already in foreground WHEN handling playing media THEN setup internal properties`() = runTestOnMain {
        val mediaTab = getMediaTab()
        val notificationsDelegate: NotificationsDelegate = mock()
        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), notificationsDelegate)
        delegate.onCreate()
        delegate.audioFocus = mock()
        delegate.isForegroundService = true

        delegate.handleMediaPlaying(mediaTab)

        verify(notificationsDelegate).notify(any(), eq(delegate.notificationId), any(), any(), any(), eq(false), eq(false))
    }

    @Test
    fun `GIVEN the service is not in foreground WHEN handling playing media THEN start the media service as foreground`() {
        val mediaTab = getMediaTab()
        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), mock())
        delegate.onCreate()
        delegate.audioFocus = mock()
        delegate.isForegroundService = false

        delegate.handleMediaPlaying(mediaTab)

        verify(delegate.service).startForeground(eq(delegate.notificationId), any())
        assertTrue(delegate.isForegroundService)
    }

    @Test
    fun `WHEN updating the notification for a new media state THEN post a new notification`() = runTestOnMain {
        val mediaTab = getMediaTab()
        val notificationsDelegate: NotificationsDelegate = mock()
        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), notificationsDelegate)
        delegate.onCreate()
        val notification: Notification = mock()
        delegate.notificationHelper = coMock {
            doReturn(notification).`when`(this).create(mediaTab, delegate.mediaSession)
        }

        delegate.updateNotification(mediaTab)

        verify(notificationsDelegate).notify(any(), eq(delegate.notificationId), eq(notification), any(), any(), eq(false), eq(false))
    }

    @Test
    fun `WHEN starting the service as foreground THEN use start with a new notification for the current media state`() = runTestOnMain {
        val mediaTab = getMediaTab()
        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), mock())
        delegate.onCreate()
        val notification: Notification = mock()
        delegate.notificationHelper = coMock {
            doReturn(notification).`when`(this).create(mediaTab, delegate.mediaSession)
        }

        delegate.startForeground(mediaTab)

        verify(delegate.service).startForeground(eq(delegate.notificationId), eq(notification))
        assertTrue(delegate.isForegroundService)
    }

    @Test
    fun `GIVEN media is paused WHEN media is handling resuming media THEN resume the right session`() {
        val mediaTab1 = getMediaTab()
        val mediaTab2 = getMediaTab(PlaybackState.PAUSED)
        val store = BrowserStore(
            BrowserState(
                tabs = listOf(mediaTab1, mediaTab2),
            ),
        )
        val service: AbstractMediaSessionService = mock()
        val crashReporter: CrashReporting = mock()
        val delegate = MediaSessionServiceDelegate(testContext, service, store, crashReporter, mock())
        val mediaSessionCallback = MediaSessionCallback(store)
        delegate.onCreate()

        mediaSessionCallback.onPause()
        verify(mediaTab1.mediaSessionState!!.controller).pause()

        mediaSessionCallback.onPlay()
        verify(mediaTab1.mediaSessionState!!.controller).play()
    }

    @Test
    fun `WHEN handling paused media THEN emit telemetry`() {
        val mediaTab = getMediaTab(PlaybackState.PAUSED)
        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), mock())

        CollectionProcessor.withFactCollection { facts ->
            delegate.handleMediaPaused(mediaTab)

            assertEquals(1, facts.size)
            with(facts[0]) {
                assertEquals(Component.FEATURE_MEDIA, component)
                assertEquals(Action.PAUSE, action)
                assertEquals(MediaFacts.Items.STATE, item)
            }
        }
    }

    @Test
    fun `WHEN handling paused media THEN update internal state and notification and stop the service`() = runTestOnMain {
        val mediaTab = getMediaTab(PlaybackState.PAUSED)
        val notificationManagerCompat = spy(NotificationManagerCompat.from(testContext))
        val notificationsDelegate = spy(NotificationsDelegate(notificationManagerCompat))
        doReturn(true).`when`(notificationManagerCompat).areNotificationsEnabled()

        val notificationHelper: MediaNotification = mock()
        val notification: Notification = mock()
        val mediaSession: MediaSessionCompat = mock()
        val notificationId = SharedIdsHelper.getIdForTag(testContext, AbstractMediaSessionService.NOTIFICATION_TAG)

        val delegate = spy(MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), notificationsDelegate))
        delegate.isForegroundService = true
        delegate.mediaSession = mediaSession
        delegate.notificationHelper = notificationHelper

        doReturn(notification).`when`(notificationHelper).create(mediaTab, mediaSession)

        delegate.onCreate()

        delegate.handleMediaPaused(mediaTab)

        verify(delegate).updateMediaSession(mediaTab)
        verify(delegate).unregisterBecomingNoisyListenerIfNeeded()
        verify(delegate.service).stopForegroundCompat(false)
        verify(notificationsDelegate).notify(null, notificationId, notification)
        assertFalse(delegate.isForegroundService)
    }

    @Test
    fun `WHEN handling stopped media THEN emit telemetry`() {
        val mediaTab = getMediaTab(PlaybackState.STOPPED)
        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), mock())

        CollectionProcessor.withFactCollection { facts ->
            delegate.handleMediaStopped(mediaTab)

            assertEquals(1, facts.size)
            with(facts[0]) {
                assertEquals(Component.FEATURE_MEDIA, component)
                assertEquals(Action.STOP, action)
                assertEquals(MediaFacts.Items.STATE, item)
            }
        }
    }

    @Test
    fun `WHEN handling stopped media THEN update internal state and notification and stop the service`() = runTestOnMain {
        val mediaTab = getMediaTab(PlaybackState.STOPPED)
        val notificationsDelegate: NotificationsDelegate = mock()

        val delegate = spy(MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), notificationsDelegate))
        delegate.isForegroundService = true
        delegate.onCreate()

        delegate.handleMediaStopped(mediaTab)

        verify(delegate).updateMediaSession(mediaTab)
        verify(delegate).unregisterBecomingNoisyListenerIfNeeded()
        verify(delegate.service).stopForegroundCompat(false)
        verify(notificationsDelegate).notify(any(), eq(notificationId), any(), any(), any(), eq(false), eq(false))
        assertFalse(delegate.isForegroundService)
    }

    @Test
    fun `WHEN there is no media playing THEN stop the media service`() {
        val notificationManagerCompat: NotificationManagerCompat = mock()
        val notificationsDelegate: NotificationsDelegate = mock()
        whenever(notificationsDelegate.notificationManagerCompat).thenReturn(notificationManagerCompat)

        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), notificationsDelegate)
        delegate.audioFocus = mock()
        delegate.mediaSession = mock()

        delegate.handleNoMedia()

        verify(delegate.mediaSession).release()
        verify(delegate.service).stopSelf()
    }

    @Suppress("Deprecation")
    @Test
    fun `WHEN updating the media session THEN use the values from the current media session`() {
        val bitmap: Bitmap = mock()
        val getArtwork: (suspend () -> Bitmap?) = { bitmap }
        val metadata = Metadata("title", "artist", "album", getArtwork)

        val mediaTab = createTab(
            title = "Mozilla",
            url = "https://www.mozilla.org",
            mediaSessionState = MediaSessionState(mock(), metadata = metadata),
        )

        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), mock())
        delegate.mediaSession = mock()
        delegate.onCreate()
        val metadataCaptor = argumentCaptor<MediaMetadataCompat>()
        // Need to capture method arguments and manually check for equality
        val playbackStateCaptor = argumentCaptor<PlaybackStateCompat>()
        val expectedPlaybackState = mediaTab.mediaSessionState!!.toPlaybackState()

        delegate.updateMediaSession(mediaTab)

        verify(delegate.mediaSession).isActive = true
        verify(delegate.mediaSession).setPlaybackState(playbackStateCaptor.capture())
        assertEquals(expectedPlaybackState.state, playbackStateCaptor.value.state)
        assertEquals(
            (expectedPlaybackState.playbackState as AndroidPlaybackState).state,
            (playbackStateCaptor.value.playbackState as AndroidPlaybackState).state,
        )
        assertEquals(
            (expectedPlaybackState.playbackState as AndroidPlaybackState).position,
            (playbackStateCaptor.value.playbackState as AndroidPlaybackState).position,
        )
        assertEquals(
            (expectedPlaybackState.playbackState as AndroidPlaybackState).playbackSpeed,
            (playbackStateCaptor.value.playbackState as AndroidPlaybackState).playbackSpeed,
        )
        assertEquals(
            (expectedPlaybackState.playbackState as AndroidPlaybackState).actions,
            (playbackStateCaptor.value.playbackState as AndroidPlaybackState).actions,
        )
        assertEquals(
            (expectedPlaybackState.playbackState as AndroidPlaybackState).customActions,
            (playbackStateCaptor.value.playbackState as AndroidPlaybackState).customActions,
        )
        assertEquals(expectedPlaybackState.playbackSpeed, playbackStateCaptor.value.playbackSpeed)
        assertEquals(expectedPlaybackState.actions, playbackStateCaptor.value.actions)
        assertEquals(expectedPlaybackState.position, playbackStateCaptor.value.position)
        verify(delegate.mediaSession).setMetadata(metadataCaptor.capture())
        assertEquals(metadata.title, metadataCaptor.value.bundle.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
        assertEquals(metadata.artist, metadataCaptor.value.bundle.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
        assertEquals(bitmap, metadataCaptor.value.bundle.getParcelable(MediaMetadataCompat.METADATA_KEY_ART))
        assertEquals(-1L, metadataCaptor.value.bundle.getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
    }

    @Test
    fun `WHEN stopping running in foreground THEN stop the foreground service`() {
        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), mock())
        delegate.isForegroundService = true

        delegate.stopForeground()

        verify(delegate.service).stopForegroundCompat(false)
        assertFalse(delegate.isForegroundService)
    }

    @Test
    fun `GIVEN a audio noisy receiver is already registered WHEN trying to register a new one THEN return early`() {
        val context = spy(testContext)
        val delegate = MediaSessionServiceDelegate(context, mock(), mock(), mock(), mock())
        delegate.noisyAudioStreamReceiver = mock()

        delegate.registerBecomingNoisyListenerIfNeeded(mock())

        verify(context, never()).registerReceiver(any(), any(), eq(Context.RECEIVER_NOT_EXPORTED))
    }

    @Test
    fun `GIVEN a audio noisy receiver is not already registered WHEN trying to register a new one THEN register it`() {
        val delegate = spy(MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), mock()))
        val receiverCaptor = argumentCaptor<BroadcastReceiver>()

        delegate.registerBecomingNoisyListenerIfNeeded(mock())

        verify(delegate).registerBecomingNoisyListener(receiverCaptor.capture())
        assertEquals(BecomingNoisyReceiver::class.java, receiverCaptor.value.javaClass)
    }

    @Test
    fun `GIVEN a audio noisy receiver is already registered WHEN trying to unregister one THEN unregister it`() {
        val context = spy(testContext)
        val delegate = MediaSessionServiceDelegate(context, mock(), mock(), mock(), mock())
        delegate.noisyAudioStreamReceiver = mock()
        context.registerReceiver(
            delegate.noisyAudioStreamReceiver,
            delegate.intentFilter,
            Context.RECEIVER_NOT_EXPORTED,
        )
        val receiverCaptor = argumentCaptor<BroadcastReceiver>()

        delegate.unregisterBecomingNoisyListenerIfNeeded()

        verify(context).unregisterReceiver(receiverCaptor.capture())
        assertEquals(BecomingNoisyReceiver::class.java, receiverCaptor.value.javaClass)
        assertNull(delegate.noisyAudioStreamReceiver)
    }

    @Test
    fun `GIVEN a audio noisy receiver is not already registered WHEN trying to unregister one THEN return early`() {
        val context = spy(testContext)
        val delegate = MediaSessionServiceDelegate(context, mock(), mock(), mock(), mock())

        delegate.unregisterBecomingNoisyListenerIfNeeded()

        verify(context, never()).unregisterReceiver(any())
    }

    @Test
    fun `WHEN the delegate is shutdown THEN cleanup resources and stop the media service`() {
        val notificationManagerCompat: NotificationManagerCompat = mock()
        val notificationsDelegate: NotificationsDelegate = mock()
        whenever(notificationsDelegate.notificationManagerCompat).thenReturn(notificationManagerCompat)

        val delegate = MediaSessionServiceDelegate(testContext, mock(), mock(), mock(), notificationsDelegate)
        delegate.mediaSession = mock()

        delegate.shutdown()

        verify(delegate.mediaSession).release()
        verify(delegate.service).stopSelf()
        assertNull(delegate.noisyAudioStreamReceiver)
    }

    @Test
    fun `when device is becoming noisy, playback is paused`() {
        val controller: MediaSession.Controller = mock()
        val initialState = BrowserState(
            tabs = listOf(
                createTab(
                    "https://www.mozilla.org",
                    mediaSessionState = MediaSessionState(controller, playbackState = PlaybackState.PLAYING),
                ),
            ),
        )
        val store = BrowserStore(initialState)
        val service: AbstractMediaSessionService = mock()
        val delegate = MediaSessionServiceDelegate(testContext, service, store, mock(), mock())
        delegate.onCreate()
        delegate.handleMediaPlaying(initialState.tabs[0])

        delegate.deviceBecomingNoisy(testContext)

        verify(controller).pause()
    }

    @Test
    fun `GIVEN device is at least API level 31 WHEN startForeground throws an exception THEN catch and pass the exception to the crash reporter`() = runTestOnMain {
        val crashReporter: CrashReporting = mock()
        val service: AbstractMediaSessionService = mock()
        val delegate = MediaSessionServiceDelegate(testContext, service, mock(), crashReporter, mock())
        delegate.onCreate()
        val notification: Notification = mock()
        delegate.notificationHelper = coMock {
            doReturn(notification).`when`(this).create(mock(), delegate.mediaSession)
        }

        val exception = ForegroundServiceStartNotAllowedException("Test thrown exception")
        doThrow(exception).`when`(service).startForeground(anyInt(), any())
        setSdkInt(31)

        delegate.startForeground(mock())

        verify(crashReporter).submitCaughtException(exception)
    }

    @Test(expected = ForegroundServiceStartNotAllowedException::class)
    fun `GIVEN device is less than 31 WHEN startForeground throws an exception THEN rethrow the exception`() {
        var throwable: Throwable? = null
        val exceptionHandler = CoroutineExceptionHandler { _, t ->
            throwable = t
        }

        runTestOnMain {
            val crashReporter: CrashReporting = mock()
            val service: AbstractMediaSessionService = mock()
            val delegate = MediaSessionServiceDelegate(testContext, service, mock(), crashReporter, mock())
            delegate.onCreate()
            val notification: Notification = mock()
            delegate.notificationHelper = coMock {
                doReturn(notification).`when`(this).create(mock(), delegate.mediaSession)
            }

            val exception = ForegroundServiceStartNotAllowedException("Test thrown exception")
            doThrow(exception).`when`(service).startForeground(anyInt(), any())
            setSdkInt(30)

            delegate.startForeground(mock(), exceptionHandler)
        }

        throwable?.let { throw it }
    }

    private fun getMediaTab(playbackState: PlaybackState = PlaybackState.PLAYING) = createTab(
        title = "Mozilla",
        url = "https://www.mozilla.org",
        mediaSessionState = MediaSessionState(mock(), playbackState = playbackState),
    )

    private fun setSdkInt(sdkVersion: Int) {
        setStaticField(Build.VERSION::SDK_INT.javaField, sdkVersion)
    }
}
