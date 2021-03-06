/*
 * Copyright (C) 2010-2014 The MPDroid Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.namelessdev.mpdroid;

import org.a0z.mpd.MPD;
import org.a0z.mpd.MPDStatus;
import org.a0z.mpd.Music;
import org.a0z.mpd.event.StatusChangeListener;
import org.a0z.mpd.exception.MPDServerException;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.util.Log;
import android.widget.RemoteViews;

import com.namelessdev.mpdroid.cover.CachedCover;
import com.namelessdev.mpdroid.helpers.CoverManager;
import com.namelessdev.mpdroid.models.MusicParcelable;
import com.namelessdev.mpdroid.tools.Tools;

/**
 * Service that handles media playback. This is the Service through which we perform all the media
 * handling in our application. Upon initialization, it waits for Intents (which come from our main
 * activity,
 * {@link MainMenuActivity}, which signal the service to perform specific operations: Play, Pause,
 * Rewind, Skip, etc.
 */
public class NotificationService extends Service implements MusicFocusable, StatusChangeListener {

    // The tag we put on debug messages
    final static String TAG = "NotificationService";

    // These are the Intent actions that we are prepared to handle.
    // Notice: they currently are a shortcut to the ones in StreamingService so that the code changes to NowPlayingFragment would be minimal.
    // TODO: change this?
    public static final String FULLY_QUALIFIED_NAME = "com.namelessdev.mpdroid.NotificationService";

    public static final String ACTION_UPDATE_INFO = FULLY_QUALIFIED_NAME + ".UPDATE_INFO";

    public static final String ACTION_TOGGLE_PLAYBACK = StreamingService.CMD_PLAYPAUSE;

    public static final String ACTION_PLAY = StreamingService.CMD_PLAY;

    public static final String ACTION_PAUSE = StreamingService.CMD_PAUSE;

    public static final String ACTION_STOP = StreamingService.CMD_STOP;

    public static final String ACTION_SHOW_NOTIFICATION = FULLY_QUALIFIED_NAME
            + ".SHOW_NOTIFICATION";

    public static final String ACTION_CLOSE_NOTIFICATION = FULLY_QUALIFIED_NAME
            + ".CLOSE_NOTIFICATION";

    public static final String ACTION_SKIP = StreamingService.CMD_NEXT;

    public static final String ACTION_REWIND = "REWIND";

    public static final String ACTION_PREVIOUS = StreamingService.CMD_PREV;

    public static final String ACTION_MUTE = "MUTE";

    public static final String ACTION_SET_VOLUME = "SET_VOLUME";

    /**
     * Extra information passed to the intent bundle: the currently playing {@link
     * org.a0z.mpd.Music}
     */
    public static final String EXTRA_CURRENT_MUSIC = FULLY_QUALIFIED_NAME + ".CurrentMusic";

    /**
     * How many milliseconds in the future we need to trigger an update when we just skipped
     * forward/backward a song
     */
    private static final long UPDATE_INFO_NEAR_FUTURE_DELAY = 500;

    Music mCurrentMusic = null, mPreviousMusic = null;

    private Bitmap mAlbumCover = null;

    private String mAlbumCoverPath;

    // our AudioFocusHelper object, if it's available (it's available on SDK level >= 8)
    // If not available, this will be null. Always check for null before using!
    AudioFocusHelper mAudioFocusHelper = null;

    private int mPreviousState = -1;

    // do we have audio focus?
    enum AudioFocus {
        NoFocusNoDuck,    // we don't have audio focus, and can't duck
        NoFocusCanDuck,   // we don't have focus, but can play at a low volume ("ducking")
        Focused           // we have full audio focus
    }

    AudioFocus mAudioFocus = AudioFocus.NoFocusNoDuck;

    // The ID we use for the notification (the onscreen alert that appears at the notification
    // area at the top of the screen as an icon -- and as text as well if the user expands the
    // notification area).
    final int NOTIFICATION_ID = 1;

    // our RemoteControlClient object, which will use remote control APIs available in
    // SDK level >= 14, if they're available.
    RemoteControlClient mRemoteControlClient;

    // The component name of MusicIntentReceiver, for use with media button and remote control APIs
    ComponentName mMediaButtonReceiverComponent;

    AudioManager mAudioManager;

    NotificationManager mNotificationManager;

    Notification mNotification = null;

    MPDApplication app;

    private MPDApplication getMpdApplication() {
        if (app == null) {
            app = (MPDApplication) getApplication();
        }
        return app;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "Creating service");

        //TODO: Acquire a network wakelock here if the user wants us to !
        //Otherwise we'll just shut down on screen off and reconnect on screen on
        //Tons of work ahead
        getMpdApplication().addConnectionLock(this);
        getMpdApplication().oMPDAsyncHelper.addStatusChangeListener(this);

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // Create the Audio Focus Helper
        mAudioFocusHelper = new AudioFocusHelper(getApplicationContext(), this);

        // Use the media button APIs (if available) to register ourselves for media button events
        mMediaButtonReceiverComponent = new ComponentName(this, RemoteControlReceiver.class);
        mAudioManager.registerMediaButtonEventReceiver(mMediaButtonReceiverComponent);
    }

    /**
     * Called when we receive an Intent. When we receive an intent sent to us via startService(),
     * this is the method that gets called. So here we react appropriately depending on the
     * Intent's action, which specifies what is being requested of us.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();

        Log.d(TAG, "received command, action=" + action + " from intent: " + intent);

        if (action == null) {
            return START_NOT_STICKY;
        } else if (action.equals(ACTION_TOGGLE_PLAYBACK)) {
            processTogglePlaybackRequest();
        } else if (action.equals(ACTION_PLAY)) {
            processPlayRequest();
        } else if (action.equals(ACTION_PAUSE)) {
            processPauseRequest();
        } else if (action.equals(ACTION_SKIP)) {
            processSkipRequest();
        } else if (action.equals(ACTION_STOP)) {
            processStopRequest();
        } else if (action.equals(ACTION_SHOW_NOTIFICATION)) {
            processShowNotificationRequest();
        } else if (action.equals(ACTION_CLOSE_NOTIFICATION)) {
            processCloseNotificationRequest();
        } else if (action.equals(ACTION_REWIND)) {
            processRewindRequest();
        } else if (action.equals(ACTION_PREVIOUS)) {
            processPreviousRequest();
        } else if (action.equals(ACTION_UPDATE_INFO)) {
            processUpdateInfo((MusicParcelable) intent.getParcelableExtra(EXTRA_CURRENT_MUSIC));
        }

        return START_NOT_STICKY; // Means we started the service, but don't want it to restart in case it's killed.
    }

    void processTogglePlaybackRequest() {
        new AsyncTask<MPDApplication, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(MPDApplication... params) {
                String state = null;
                try {
                    state = params[0].oMPDAsyncHelper.oMPD.getStatus().getState();
                } catch (MPDServerException e) {
                    Log.w(MPDApplication.TAG, e.getMessage());
                }
                return MPDStatus.MPD_STATE_PLAYING.equals(state) || MPDStatus.MPD_STATE_PAUSED
                        .equals(state);
            }

            @Override
            protected void onPostExecute(Boolean shouldPause) {
                if (shouldPause) {
                    processPauseRequest();
                } else {
                    processPlayRequest();
                }
            }
        }.execute((MPDApplication) getApplication());
    }

    void processPlayRequest() {
        tryToGetAudioFocus();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final MPD mpd = getMpdApplication().oMPDAsyncHelper.oMPD;
                    String state = mpd.getStatus().getState();
                    if (!MPDStatus.MPD_STATE_PLAYING.equals(state)) {
                        mpd.play();
                    }
                } catch (MPDServerException e) {
                    Log.w(MPDApplication.TAG, e.getMessage());
                }
            }
        }).start();

        updatePlayingInfo(RemoteControlClient.PLAYSTATE_PLAYING);
    }

    void processPauseRequest() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    getMpdApplication();
                    if (app != null) {
                        app.oMPDAsyncHelper.oMPD.pause();
                    }
                } catch (MPDServerException e) {
                    Log.w(MPDApplication.TAG, e.getMessage());
                }
            }
        }).start();

        updatePlayingInfo(RemoteControlClient.PLAYSTATE_PAUSED);
    }

    void processUpdateInfo(MusicParcelable music) {
        Log.d(TAG, "parcelable=" + music + " mCurrentMusic=" + mCurrentMusic);
        if (mCurrentMusic != null && (mCurrentMusic).equals(music)) {
            return;
        }
        mCurrentMusic = music;
        updatePlayingInfo(RemoteControlClient.PLAYSTATE_PLAYING);
    }

    void processRewindRequest() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final MPDApplication app = (MPDApplication) getApplication();
                    if (app != null) {
                        app.oMPDAsyncHelper.oMPD.seek(0);
                    }
                } catch (MPDServerException e) {
                    Log.w(MPDApplication.TAG, e.getMessage());
                }
            }
        }).start();
        updatePlayingInfo(RemoteControlClient.PLAYSTATE_REWINDING);

        final Intent service = new Intent(this, NotificationService.class);
        service.setAction(ACTION_UPDATE_INFO);
        startService(service);
    }

    void processPreviousRequest() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final MPDApplication app = (MPDApplication) getApplication();
                    if (app != null) {
                        app.oMPDAsyncHelper.oMPD.previous();
                    }
                } catch (MPDServerException e) {
                    Log.w(MPDApplication.TAG, e.getMessage());
                }
            }
        }).start();

        final Intent service = new Intent(this, NotificationService.class);
        service.setAction(ACTION_UPDATE_INFO);
        startService(service);
    }

    void processSkipRequest() {
        tryToGetAudioFocus();

        getMpdApplication();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (app != null) {
                        app.oMPDAsyncHelper.oMPD.next();
                    }
                } catch (MPDServerException e) {
                    Log.w(MPDApplication.TAG, e.getMessage());
                }
            }
        }).start();
        triggerFutureUpdate();
    }

    void processStopRequest() {
        final MPDApplication app = (MPDApplication) getApplication();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (app != null) {
                        app.oMPDAsyncHelper.oMPD.stop();
                    }
                } catch (MPDServerException e) {
                    Log.w(MPDApplication.TAG, e.getMessage());
                }
            }
        }).start();

        // let go of all resources...
        relaxResources();
        giveUpAudioFocus();

        // Tell any remote controls that our playback state is 'paused'.
        if (mRemoteControlClient != null) {
            mRemoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
        }

        // service is no longer necessary. Will be started again if needed.
        stopSelf();
    }

    void processShowNotificationRequest() {
        tryToGetAudioFocus();
        processUpdateInfo(null);
    }

    void processCloseNotificationRequest() {
        // let go of all resources...
        relaxResources();
        giveUpAudioFocus();

        // Tell any remote controls that our playback state is 'paused'.
        if (mRemoteControlClient != null) {
            mRemoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
        }

        // service is no longer necessary. Will be started again if needed.
        stopSelf();
    }

    /**
     * Launch the service again with action {@link #ACTION_UPDATE_INFO} in a near future
     */
    private void triggerFutureUpdate() {
        // Don't updatePlayingInfo right now, but rather trigger an update in a small delay
        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                final Intent service = new Intent(NotificationService.this,
                        NotificationService.class);
                service.setAction(ACTION_UPDATE_INFO);
                startService(service);
            }
        }, UPDATE_INFO_NEAR_FUTURE_DELAY);
    }

    /**
     * Releases resources used by the service for playback. This includes the "foreground service"
     * status and notification, the wake locks and possibly the MediaPlayer.
     */
    void relaxResources() {
        Log.d(TAG, "Removing connection lock");
        getMpdApplication().removeConnectionLock(this);
        getMpdApplication().oMPDAsyncHelper.removeStatusChangeListener(this);
        stopForeground(true);
        if (mAlbumCover != null && !mAlbumCover.isRecycled()) {
            mAlbumCover.recycle();
            mAlbumCover = null;
        }
    }

    void giveUpAudioFocus() {
        if (mAudioFocus == AudioFocus.Focused && mAudioFocusHelper != null && mAudioFocusHelper
                .abandonFocus()) {
            mAudioFocus = AudioFocus.NoFocusNoDuck;
        }
    }

    void tryToGetAudioFocus() {
        if (mAudioFocus != AudioFocus.Focused && mAudioFocusHelper != null && mAudioFocusHelper
                .requestFocus()) {
            mAudioFocus = AudioFocus.Focused;
        }
    }

    void updatePlayingInfo(int state) {
        Log.d(TAG, "update playing info: state=" + state + " (previous state: " + mPreviousState
                + "), music=" + mCurrentMusic + " (previous music: " + mPreviousMusic + ")");

        // Create the remote control client
        if (mRemoteControlClient == null) {
            Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            intent.setComponent(mMediaButtonReceiverComponent);
            mRemoteControlClient = new RemoteControlClient(PendingIntent
                    .getBroadcast(getApplicationContext() /*context*/, 0 /*requestCode, ignored*/, intent /*intent*/, 0 /*flags*/));
            mRemoteControlClient.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY |
                    RemoteControlClient.FLAG_KEY_MEDIA_PAUSE |
                    RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS |
                    RemoteControlClient.FLAG_KEY_MEDIA_NEXT |
                    RemoteControlClient.FLAG_KEY_MEDIA_STOP);

            mAudioManager.registerRemoteControlClient(mRemoteControlClient);
        }

        //TODO: load this from a background thread
        if (mCurrentMusic == null) {
            final MPDApplication app = (MPDApplication) getApplication();
            if (app != null) {
                try {
                    final MPDStatus status = app.oMPDAsyncHelper.oMPD.getStatus();
                    final int songPos = status.getSongPos();
                    if (songPos >= 0) {
                        mCurrentMusic = app.oMPDAsyncHelper.oMPD.getPlaylist().getByIndex(songPos);
                    }
                } catch (MPDServerException e) {
                    Log.w("NotificationService",
                            "MPDServerException playing next song: " + e.getMessage());
                }
            }
        }

        // Clear everything if we stopped
        if (state == RemoteControlClient.PLAYSTATE_STOPPED) {
            if (mNotificationManager != null) {
                mNotificationManager.cancel(NOTIFICATION_ID);
            }
            relaxResources();
            giveUpAudioFocus();
            stopSelf();
        }
        // Otherwise, update notification & lockscreen widget
        else {
            if (mAlbumCover != null && !mAlbumCover.isRecycled()) {
                mAlbumCover.recycle();
            }

            // The code below is copied from StreamingService (thanks! :P)
            if (mCurrentMusic != null) {
                // Check if we have a sdcard cover cache for this song
                // Maybe find a more efficient way
                final MPDApplication app = (MPDApplication) getApplication();
                final SharedPreferences settings = PreferenceManager
                        .getDefaultSharedPreferences(app);
                if (settings.getBoolean(CoverManager.PREFERENCE_CACHE, true)) {
                    final CachedCover cache = new CachedCover(app);
                    final String[] coverArtPath;
                    try {
                        coverArtPath = cache.getCoverUrl(mCurrentMusic.getAlbumInfo());
                        if (coverArtPath != null && coverArtPath.length > 0
                                && coverArtPath[0] != null) {
                            mAlbumCoverPath = coverArtPath[0];
                            mAlbumCover = Tools
                                    .decodeSampledBitmapFromPath(coverArtPath[0], getResources()
                                            .getDimensionPixelSize(
                                                    android.R.dimen.notification_large_icon_width),
                                            getResources()
                                                    .getDimensionPixelSize(
                                                            android.R.dimen.notification_large_icon_height),
                                            true);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            updateNotification(state);
            updateRemoteControlClient(state);
        }

        mPreviousMusic = mCurrentMusic;
        mPreviousState = state;
    }

    private RemoteViews buildCollapsedNotification(PendingIntent piPlayPause, PendingIntent piNext,
            PendingIntent piCloseNotification, int playPauseResId) {
        final RemoteViews contentView;
        if (mNotification == null || mNotification.contentView == null) {
            contentView = new RemoteViews(getPackageName(), R.layout.notification);
        } else {
            contentView = mNotification.contentView;
        }

        contentView.setTextViewText(R.id.notificationTitle, mCurrentMusic.getTitle());
        contentView.setTextViewText(R.id.notificationArtist, mCurrentMusic.getArtist());

        contentView.setOnClickPendingIntent(R.id.notificationPlayPause, piPlayPause);
        contentView.setOnClickPendingIntent(R.id.notificationNext, piNext);
        contentView.setOnClickPendingIntent(R.id.notificationClose, piCloseNotification);

        contentView.setImageViewResource(R.id.notificationPlayPause, playPauseResId);

        return contentView;
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private RemoteViews buildExpandedNotification(PendingIntent piPrev, PendingIntent piPlayPause,
            PendingIntent piNext, PendingIntent piCloseNotification, int playPauseResId) {
        final RemoteViews contentView;
        if (mNotification == null || mNotification.bigContentView == null) {
            contentView = new RemoteViews(getPackageName(), R.layout.notification_big);
        } else {
            contentView = mNotification.bigContentView;
        }

        contentView.setTextViewText(R.id.notificationTitle, mCurrentMusic.getTitle());
        contentView.setTextViewText(R.id.notificationArtist, mCurrentMusic.getArtist());
        contentView.setTextViewText(R.id.notificationAlbum, mCurrentMusic.getAlbum());

        contentView.setOnClickPendingIntent(R.id.notificationPrev, piPrev);
        contentView.setOnClickPendingIntent(R.id.notificationPlayPause, piPlayPause);
        contentView.setOnClickPendingIntent(R.id.notificationNext, piNext);
        contentView.setOnClickPendingIntent(R.id.notificationClose, piCloseNotification);

        contentView.setImageViewResource(R.id.notificationPlayPause, playPauseResId);

        return contentView;
    }

    /**
     * Update the notification.
     *
     * @param state The new current playing state
     */
    private void updateNotification(int state) {
        Log.d(TAG, "update notification: " + mCurrentMusic.getArtist() + " - " + mCurrentMusic
                .getTitle() + ", state: " + state);

        // Build a virtual task stack
        final Intent musicPlayerActivity = new Intent(getApplicationContext(),
                MainMenuActivity.class);
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addParentStack(MainMenuActivity.class);
        stackBuilder.addNextIntent(musicPlayerActivity);

        // Build notification actions
        final Intent playPause = new Intent(this, NotificationService.class);
        playPause.setAction(NotificationService.ACTION_TOGGLE_PLAYBACK);
        final PendingIntent piPlayPause = PendingIntent.getService(this, 0, playPause, 0);
        final Intent prev = new Intent(this, NotificationService.class);
        prev.setAction(ACTION_PREVIOUS);
        final PendingIntent piPrev = PendingIntent.getService(this, 0, prev, 0);
        final Intent next = new Intent(this, NotificationService.class);
        next.setAction(NotificationService.ACTION_SKIP);
        final PendingIntent piNext = PendingIntent.getService(this, 0, next, 0);
        final Intent closeNotification = new Intent(this, NotificationService.class);
        closeNotification.setAction(NotificationService.ACTION_CLOSE_NOTIFICATION);
        final PendingIntent piCloseNotification = PendingIntent
                .getService(this, 0, closeNotification, 0);
        PendingIntent piClick = stackBuilder.getPendingIntent(0,
                PendingIntent.FLAG_UPDATE_CURRENT); // click on notification itself

        // Set notification play/pause icon state
        final int playPauseResId = state == RemoteControlClient.PLAYSTATE_PLAYING
                ? R.drawable.ic_media_pause : R.drawable.ic_media_play;

        // Create the views
        RemoteViews collapsedNotification = buildCollapsedNotification(piPlayPause, piNext,
                piCloseNotification, playPauseResId);
        RemoteViews expandedNotification = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            expandedNotification = buildExpandedNotification(piPrev, piPlayPause, piNext,
                    piCloseNotification, playPauseResId);
        }

        // Set notification icon, if we have one
        if (mAlbumCover != null) {
            collapsedNotification
                    .setImageViewUri(R.id.notificationIcon, Uri.parse(mAlbumCoverPath));
            if (expandedNotification != null) {
                expandedNotification
                        .setImageViewUri(R.id.notificationIcon, Uri.parse(mAlbumCoverPath));
            }
        }

        // Finish the notification
        if (mNotification == null) {
            final NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
            builder.setSmallIcon(R.drawable.icon_bw);
            builder.setContentIntent(piClick);
            builder.setContent(collapsedNotification);

            builder.setStyle(new NotificationCompat.BigTextStyle());
//            builder.setStyle(new Notification.BigTextStyle().bigText(mCurrentMusic.getArtist()).setBigContentTitle(mCurrentMusic.getTitle()));
//            builder.addAction(R.drawable.ic_media_previous, "", piPrev);
//            builder.addAction(playPauseResId, "", piPlayPause);
//            builder.addAction(R.drawable.ic_media_next, "", piNext);

            mNotification = builder.build();
        }

        setBigContentView(mNotification, expandedNotification);

        mNotificationManager.notify(NOTIFICATION_ID, mNotification);
        startForeground(NOTIFICATION_ID, mNotification);
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void setBigContentView(Notification notif, RemoteViews view) {
        if (view != null) {
            notif.bigContentView = view;
        }
    }

    /**
     * Update the remote controls
     *
     * @param state The new current playing state
     */
    private void updateRemoteControlClient(int state) {
        mRemoteControlClient.editMetadata(true) //
                .putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, mCurrentMusic.getArtist()) //
                .putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, mCurrentMusic.getAlbum()) //
                .putString(MediaMetadataRetriever.METADATA_KEY_TITLE, mCurrentMusic.getTitle()) //
                .putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, mCurrentMusic.getTime()) //
                .putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, mAlbumCover) //
                .apply();
        mRemoteControlClient.setPlaybackState(state);
        Log.d(TAG, "Updated remote client with state " + state + " for music " + mCurrentMusic);
    }

    public void onGainedAudioFocus() {
        //Toast.makeText(this, "gained audio focus.", Toast.LENGTH_SHORT).show();
        mAudioFocus = AudioFocus.Focused;
    }

    public void onLostAudioFocus(boolean canDuck) {
        //Toast.makeText(this, "lost audio focus." + (canDuck ? "can duck" : "no duck"), Toast.LENGTH_SHORT).show();
        mAudioFocus = canDuck ? AudioFocus.NoFocusCanDuck : AudioFocus.NoFocusNoDuck;
    }

    @Override
    public void onDestroy() {
        // Service is being killed, so make sure we release our resources
        relaxResources();
        giveUpAudioFocus();
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    /**
     * StatusChangeListener methods
     */
    @Override
    public void connectionStateChanged(boolean connected, boolean connectionLost) {
        //TODO : Probably do something here
    }

    @Override
    public void libraryStateChanged(boolean updating) {
        // We do not care about that event
    }

    @Override
    public void playlistChanged(MPDStatus mpdStatus, int oldPlaylistVersion) {
        // We do not care about that event
    }

    @Override
    public void randomChanged(boolean random) {
        // We do not care about that event
    }

    @Override
    public void repeatChanged(boolean repeating) {
        // We do not care about that event
    }

    @Override
    public void stateChanged(MPDStatus mpdStatus, String oldState) {
        updatePlayingInfo(mpdStatus.getState().equals(MPDStatus.MPD_STATE_PLAYING) ?
                RemoteControlClient.PLAYSTATE_PLAYING : RemoteControlClient.PLAYSTATE_PAUSED);
    }

    @Override
    public void trackChanged(MPDStatus mpdStatus, int oldTrack) {
        if (mpdStatus.getPlaylistLength() == 0) {
            updatePlayingInfo(RemoteControlClient.PLAYSTATE_STOPPED);
        } else {
            final int songPos = mpdStatus.getSongPos();
            if (songPos >= 0) {
                mCurrentMusic = app.oMPDAsyncHelper.oMPD.getPlaylist().getByIndex(songPos);
            }
            stateChanged(mpdStatus, null);
        }
    }

    @Override
    public void volumeChanged(MPDStatus mpdStatus, int oldVolume) {
        // We do not care about that event
    }
}
