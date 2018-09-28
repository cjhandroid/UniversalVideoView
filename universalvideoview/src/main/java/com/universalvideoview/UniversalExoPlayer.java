/*
* Copyright (C) 2015 Author <dictfb#gmail.com>
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/


package com.universalvideoview;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import java.util.Map;


public class UniversalExoPlayer extends SurfaceView
        implements UniversalMediaController.MediaPlayerControl, OrientationDetector.OrientationChangeListener, AudioManager.OnAudioFocusChangeListener {
    private String TAG = "UniversalVideoView";
    // settable by the client
    private Uri mUri;
    private String mUrl;

    private AudioManager mAudioManager;

    // all possible internal states
    private static final int STATE_ERROR = -1;
    private static final int STATE_IDLE = 0;
    private static final int STATE_PREPARING = 1;
    private static final int STATE_PREPARED = 2;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_PAUSED = 4;
    private static final int STATE_PLAYBACK_COMPLETED = 5;

    // mCurrentState is a VideoView object's current state.
    // mTargetState is the state that a method caller intends to reach.
    // For instance, regardless the VideoView object's current state,
    // calling pause() intends to bring the object to a target state
    // of STATE_PAUSED.
    private int mCurrentState = STATE_IDLE;
    private int mTargetState = STATE_IDLE;

    // All the stuff we need for playing and showing a video
    private SurfaceHolder mSurfaceHolder = null;
    private SimpleExoPlayer mMediaPlayer = null;
    private int mAudioSession;
    private int mVideoWidth;
    private int mVideoHeight;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private UniversalMediaController mMediaController;
    private int mCurrentBufferPercentage;
    private int mSeekWhenPrepared;  // recording the seek position while preparing
    private boolean mCanPause;
    private boolean mCanSeekBack;
    private boolean mCanSeekForward;
    private Context mContext;
    private boolean mAutoRotation = false;
    private int mVideoViewLayoutWidth = 0;
    private int mVideoViewLayoutHeight = 0;

    private OrientationDetector mOrientationDetector;
    private VideoViewCallback videoViewCallback;

    private int wantWidth, wantHeight;

    /**
     * Whether we adjust our view bounds or we fill the remaining area with black bars
     */
    private boolean mAdjustViewBounds;

    public UniversalExoPlayer(Context context) {
        this(context, null);
    }

    public UniversalExoPlayer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UniversalExoPlayer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mContext = context;
        TypedArray a = mContext.obtainStyledAttributes(attrs, R.styleable.UniversalVideoView, 0, 0);
        mAutoRotation = a.getBoolean(R.styleable.UniversalVideoView_uvv_autoRotation, false);
        a.recycle();
        initVideoView();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mMediaPlayer != null) {
            final int videoWidth = mVideoWidth;
            final int videoHeight = mVideoHeight;
//            Log.e("onMeasure", "videoWidth: " + mVideoWidth);
//            Log.e("onMeasure", "mVideoHeight: " + mVideoHeight);
            if (videoWidth != 0 && videoHeight != 0) {
                final float aspectRatio = (float) videoHeight / videoWidth;
                final int width = MeasureSpec.getSize(widthMeasureSpec);
                final int height = MeasureSpec.getSize(heightMeasureSpec);
                final float viewRatio = (float) height / width;
                if (aspectRatio > viewRatio) {
                    int padding = (int) ((width - height / aspectRatio) / 2);
                    setPadding(padding, 0, padding, 0);
                } else {
                    int padding = (int) ((height - width * aspectRatio) / 2);
                    setPadding(0, padding, 0, padding);
                }
                onMeasureKeepAspectRatio(widthMeasureSpec, heightMeasureSpec);
                return;
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void onMeasureKeepAspectRatio(int widthMeasureSpec, int heightMeasureSpec) {
        //Log.i("@@@@", "onMeasure(" + MeasureSpec.toString(widthMeasureSpec) + ", "
        //        + MeasureSpec.toString(heightMeasureSpec) + ")");

        int width = getDefaultSize(mVideoWidth, widthMeasureSpec);
        int height = getDefaultSize(mVideoHeight, heightMeasureSpec);
        if (mVideoWidth > 0 && mVideoHeight > 0) {

            int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
            int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);
            int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
            int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);

            if (widthSpecMode == MeasureSpec.EXACTLY && heightSpecMode == MeasureSpec.EXACTLY) {
                // the size is fixed
                width = widthSpecSize;
                height = heightSpecSize;

                // for compatibility, we adjust size based on aspect ratio
                if (mVideoWidth * height < width * mVideoHeight) {
                    //Log.i("@@@", "image too wide, correcting");
                    width = height * mVideoWidth / mVideoHeight;
                } else if (mVideoWidth * height > width * mVideoHeight) {
                    //Log.i("@@@", "image too tall, correcting");
                    height = width * mVideoHeight / mVideoWidth;
                }
            } else if (widthSpecMode == MeasureSpec.EXACTLY) {
                // only the width is fixed, adjust the height to match aspect ratio if possible
                width = widthSpecSize;
                height = width * mVideoHeight / mVideoWidth;
                if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
                    // couldn't match aspect ratio within the constraints
                    height = heightSpecSize;
                }
            } else if (heightSpecMode == MeasureSpec.EXACTLY) {
                // only the height is fixed, adjust the width to match aspect ratio if possible
                height = heightSpecSize;
                width = height * mVideoWidth / mVideoHeight;
                if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
                    // couldn't match aspect ratio within the constraints
                    width = widthSpecSize;
                }
            } else {
                // neither the width nor the height are fixed, try to use actual video size
                width = mVideoWidth;
                height = mVideoHeight;
                if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
                    // too tall, decrease both width and height
                    height = heightSpecSize;
                    width = height * mVideoWidth / mVideoHeight;
                }
                if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
                    // too wide, decrease both width and height
                    width = widthSpecSize;
                    height = width * mVideoHeight / mVideoWidth;
                }
            }
        } else {
            // no size yet, just adopt the given spec sizes
        }
        setMeasuredDimension(width, height);
    }

    public void setAdjustViewBounds(boolean adjustViewBounds) {
        if (mAdjustViewBounds == adjustViewBounds) {
            return;
        }
        mAdjustViewBounds = adjustViewBounds;
        if (adjustViewBounds) {
            setBackground(null);
        } else {
            setBackgroundColor(Color.BLACK);
        }
        requestLayout();
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(UniversalExoPlayer.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(UniversalExoPlayer.class.getName());
    }

    private void initVideoView() {
        mVideoWidth = 0;
        mVideoHeight = 0;
        getHolder().addCallback(mSHCallback);
        getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        mCurrentState = STATE_IDLE;
        mTargetState = STATE_IDLE;
    }

    @Override
    public void onOrientationChanged(int screenOrientation, OrientationDetector.Direction direction) {
        if (!mAutoRotation) {
            return;
        }
    }

    /**
     * Sets video path.
     *
     * @param path the path of the video.
     */
    public void setVideoPath(String path) {
        mUrl = path;
        setVideoURI(Uri.parse(path));
    }

    /**
     * Sets video URI.
     *
     * @param uri the URI of the video.
     */
    public void setVideoURI(Uri uri) {
        setVideoURI(uri, null);
    }

    /**
     * Sets video URI using specific headers.
     *
     * @param uri     the URI of the video.
     * @param headers the headers for the URI request.
     *                Note that the cross domain redirection is allowed by default, but that can be
     *                changed with key/value pairs through the headers parameter with
     *                "android-allow-cross-domain-redirect" as the key and "0" or "1" as the value
     *                to disallow or allow cross domain redirection.
     */
    public void setVideoURI(Uri uri, Map<String, String> headers) {
        mUri = uri;
        mSeekWhenPrepared = 0;
        openVideo();
        requestLayout();
        invalidate();
    }


    public void stopPlayback() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = null;
            mCurrentState = STATE_IDLE;
            mTargetState = STATE_IDLE;
            if (mAudioManager != null) {
                mAudioManager.abandonAudioFocus(this);
            }
        }
    }

    private void openVideo() {
        if (mUri == null || mSurfaceHolder == null) {
            // not ready for playback just yet, will try again later
            return;
        }

        // we shouldn't clear the target state, because somebody might have
        // called start() previously
        release(false);
        try {
            mMediaPlayer = getExoPlayerInstance();

            requestAudioFocus();
            setVideoPath();

            // we don't set the target state here either, but preserve the
            // target state that was there before.
            mCurrentState = STATE_PREPARING;
            attachMediaController();
        } catch (Exception ex) {
            Log.w(TAG, "Unable to open content: " + mUri, ex);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
        }
    }

    public void setMediaController(UniversalMediaController controller) {
        if (mMediaController != null) {
            mMediaController.hide();
        }
        mMediaController = controller;
        attachMediaController();
    }

    private void attachMediaController() {
        if (mMediaPlayer != null && mMediaController != null) {
            mMediaController.setMediaPlayer(this);
            mMediaController.setEnabled(isInPlaybackState());
            mMediaController.hide();
        }
    }

    /**
     * Register a callback to be invoked when an error occurs
     * during playback or setup.  If no listener is specified,
     * or if the listener returned false, VideoView will inform
     * the user of any errors.
     *
     * @param l The callback that will be run
     */
    SurfaceHolder.Callback mSHCallback = new SurfaceHolder.Callback() {
        public void surfaceChanged(SurfaceHolder holder, int format,
                                   int w, int h) {
            mSurfaceWidth = w;
            mSurfaceHeight = h;
            boolean isValidState = (mTargetState == STATE_PLAYING);
            boolean hasValidSize = (mVideoWidth == w && mVideoHeight == h);
            if (mMediaPlayer != null && isValidState && hasValidSize) {
                if (mSeekWhenPrepared != 0) {
                    seekTo(mSeekWhenPrepared);
                }
            }
        }

        public void surfaceCreated(SurfaceHolder holder) {
            mSurfaceHolder = holder;
            openVideo();
            enableOrientationDetect();
        }

        public void surfaceDestroyed(SurfaceHolder holder) {
            // after we return from this we can't use the surface any more
            mSurfaceHolder = null;
            if (mMediaController != null) mMediaController.hide();
            release(true);
            disableOrientationDetect();
        }
    };

    private void enableOrientationDetect() {
        if (mAutoRotation) {
            mOrientationDetector = new OrientationDetector(mContext);
            mOrientationDetector.setOrientationChangeListener(UniversalExoPlayer.this);
            mOrientationDetector.enable();
        }
    }

    private void disableOrientationDetect() {
        if (mOrientationDetector != null) {
            mOrientationDetector.disable();
        }
    }

    /*
     * release the media player in any state
     */
    public void release(boolean cleartargetstate) {
        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
            mCurrentState = STATE_IDLE;
            if (cleartargetstate) {
                mTargetState = STATE_IDLE;
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (isInPlaybackState() && mMediaController != null) {
            toggleMediaControlsVisibility();
        }
        return false;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent ev) {
        if (isInPlaybackState() && mMediaController != null) {
            toggleMediaControlsVisibility();
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean isKeyCodeSupported = keyCode != KeyEvent.KEYCODE_BACK &&
                keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
                keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
                keyCode != KeyEvent.KEYCODE_VOLUME_MUTE &&
                keyCode != KeyEvent.KEYCODE_MENU &&
                keyCode != KeyEvent.KEYCODE_CALL &&
                keyCode != KeyEvent.KEYCODE_ENDCALL;
        if (isInPlaybackState() && isKeyCodeSupported && mMediaController != null) {
            if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (mMediaPlayer.isPlayingAd()) {
                    pause();
                    mMediaController.show();
                } else {
                    start();
                    mMediaController.hide();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                if (!mMediaPlayer.isPlayingAd()) {
                    start();
                    mMediaController.hide();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (mMediaPlayer.isPlayingAd()) {
                    pause();
                    mMediaController.show();
                }
                return true;
            } else {
                toggleMediaControlsVisibility();
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    private void toggleMediaControlsVisibility() {
        if (mMediaController.isShowing()) {
            mMediaController.hide();
        } else {
            mMediaController.show();
        }
    }

    @Override
    public boolean isPlaying() {
        boolean isInPlaybackState = isInPlaybackState();
        int playbackState = Player.STATE_READY;
        if (mMediaPlayer != null)
            playbackState = mMediaPlayer.getPlaybackState();
        boolean isPlaying = (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY);
        Log.e("Universal isPlaying()", "mCurrentState = " + mCurrentState + " ||| isInPlaybackState = " + isInPlaybackState + " ||| isPlaying = " + isPlaying);
        return isInPlaybackState && isPlaying;
    }

    @Override
    public void start() {
        int result = requestAudioFocus();
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.e("Universal start()", "result = " + result);
            return;
        }
        if (mMediaPlayer != null && mMediaPlayer.getPlaybackState() == Player.STATE_ENDED){
            mMediaPlayer.seekTo(0);
        }

        mMediaPlayer.setPlayWhenReady(true);
        mCurrentState = STATE_PLAYING;
        mTargetState = STATE_PLAYING;
        Log.e("Universal start()", "start mCurrentState = " + mCurrentState);
    }

    public int requestAudioFocus() {
        if (mAudioManager == null)
            mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        return mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    @Override
    public void pause() {
        if (mMediaPlayer != null)
            mMediaPlayer.setPlayWhenReady(false);
        mCurrentState = STATE_PAUSED;
        mTargetState = STATE_PAUSED;
        Log.e("Universal pause()", "pause mCurrentState = " + mCurrentState);
    }

    public void showLoading() {
        if (mMediaController != null) {
            mMediaController.showLoading();
        }
    }

    public int getCurrentState() {
        Log.e("getmCurrentState", mCurrentState + "");
        return mCurrentState;
    }

    @Override
    public int getDuration() {
        if (isInPlaybackState()) {
            return (int) mMediaPlayer.getDuration();
        }

        return -1;
    }

    @Override
    public int getCurrentPosition() {
        if (isInPlaybackState()) {
            return (int) mMediaPlayer.getCurrentPosition();
        }
        return 0;
    }

    @Override
    public void seekTo(int msec) {
        if (isInPlaybackState()) {
            mMediaPlayer.seekTo(msec);
            mSeekWhenPrepared = 0;
        } else {
            mSeekWhenPrepared = msec;
        }
    }


    @Override
    public int getBufferPercentage() {
        if (mMediaPlayer != null) {
            return mCurrentBufferPercentage;
        }
        return 0;
    }

    private boolean isInPlaybackState() {
        return (mMediaPlayer != null &&
                mCurrentState != STATE_ERROR &&
                mCurrentState != STATE_IDLE
                && mCurrentState != STATE_PAUSED
        );
    }

    @Override
    public boolean canPause() {
        return mCanPause;
    }

    @Override
    public boolean canSeekBackward() {
        return mCanSeekBack;
    }

    @Override
    public boolean canSeekForward() {
        return mCanSeekForward;
    }

    @Override
    public void closePlayer() {
        release(true);
    }

    @Override
    public void setFullscreen(boolean fullscreen) {
        int screenOrientation = fullscreen ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        setFullscreen(fullscreen, screenOrientation);
    }

    @Override
    public void setFullscreen(boolean fullscreen, int screenOrientation) {
        if (fullscreen) {
            if (mVideoViewLayoutWidth == 0 && mVideoViewLayoutHeight == 0) {
                ViewGroup.LayoutParams params = getLayoutParams();
                mVideoViewLayoutWidth = params.width;//保存全屏之前的参数
                mVideoViewLayoutHeight = params.height;
            }
        } else {
            ViewGroup.LayoutParams params = getLayoutParams();
            params.width = mVideoViewLayoutWidth;//使用全屏之前的参数
            params.height = mVideoViewLayoutHeight;
            setLayoutParams(params);
        }
        if (mMediaController != null) {
            mMediaController.toggleButtons(fullscreen);
        }
        if (videoViewCallback != null) {
            videoViewCallback.onScaleChange(fullscreen);
        }
    }

    public void minimize() {
        if (videoViewCallback != null) {
            videoViewCallback.onMinimized();
        }
    }

    public interface VideoViewCallback {
        void onScaleChange(boolean isFullscreen);

        void onMinimized();

        void onPause(final MediaPlayer mediaPlayer);

        void onStart(final MediaPlayer mediaPlayer);

        void onBufferingStart(final MediaPlayer mediaPlayer);

        void onBufferingEnd(final MediaPlayer mediaPlayer);
    }

    public void setVideoViewCallback(VideoViewCallback callback) {
        this.videoViewCallback = callback;
    }

    /**
     * Called by AudioManager on audio focus changes.
     * Implementation of {@link AudioManager.OnAudioFocusChangeListener}
     */
    @Override
    public void onAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            pause();
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pause();
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (mCurrentState != STATE_ERROR) {
                try {
                    mMediaPlayer.setVolume(0.2f);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            if (mCurrentState != STATE_ERROR) {
                try {
                    mMediaPlayer.setVolume(1f);
                    Log.e("onAudioFocusChange", "start()");
                    start();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 创建 ExoPlayer 实例
     *
     * @return
     */
    private SimpleExoPlayer getExoPlayerInstance() {

// 创建带宽
        BandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();

// 创建轨道选择工厂
        TrackSelection.Factory videoTrackSelectionFactory = new AdaptiveTrackSelection.Factory(bandwidthMeter);

// 创建轨道选择器实例
        TrackSelector trackSelector = new DefaultTrackSelector(videoTrackSelectionFactory);

//step2. 创建播放器

        return ExoPlayerFactory.newSimpleInstance(getContext(), trackSelector);
    }

    /**
     * 为 ExoPlayer 设置URI
     *
     * @return
     */
    private void setVideoPath() {
        showLoading();
        // 测量播放带宽，如果不需要可以传null
        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();

// 创建加载数据的工厂
        DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(getContext(),
                Util.getUserAgent(getContext(), "yourApplicationName"), bandwidthMeter);

// 创建解析数据的工厂
        ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();

// 传入Uri、加载数据的工厂、解析数据的工厂，就能创建出MediaSource
        MediaSource videoSource = new ExtractorMediaSource(mUri,
                dataSourceFactory, extractorsFactory, null, null);

        mMediaPlayer.setVideoSurfaceHolder(mSurfaceHolder);

// Prepare
        mMediaPlayer.setPlayWhenReady(true);
        mMediaPlayer.prepare(videoSource);
        mMediaPlayer.addVideoListener(new SimpleExoPlayer.VideoListener() {
            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    int rotationDegrees = mMediaPlayer.getVideoFormat().rotationDegrees;
                    Log.e("onVideoSizeChanged", "rotationDegrees = " + rotationDegrees);
                    if (rotationDegrees == 90) {

                    }
                }

                if (wantHeight > 0 && wantWidth > 0) {
                    mVideoWidth = wantWidth;
                    mVideoHeight = wantHeight;
                } else {
                    mVideoWidth = width;
                    mVideoHeight = height;
                }
                requestLayout();
            }

            @Override
            public void onRenderedFirstFrame() {

            }
        });
        mMediaPlayer.addListener(new Player.EventListener() {
            @Override
            public void onTimelineChanged(Timeline timeline, Object manifest) {
                Log.e("onTimelineChanged", "onTimelineChanged");
            }

            @Override
            public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
                Log.e("onTracksChanged", "onTracksChanged");
            }

            @Override
            public void onLoadingChanged(boolean isLoading) {
                Log.e("onLoadingChanged", isLoading + "");
            }

            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
//                Log.e("onPlayerStateChanged", "playbackState = " + playbackState);
                switch (playbackState) {
                    case Player.STATE_BUFFERING:
                        mCurrentBufferPercentage = getCurrentPosition();
                        break;

                    case Player.STATE_READY:
                        readyWithPrepared();
                        break;

                    case Player.STATE_ENDED:
                        mCurrentState = STATE_PLAYBACK_COMPLETED;
                        mTargetState = STATE_PLAYBACK_COMPLETED;
                        if (mMediaController != null) {
                            mMediaController.showComplete();
                        }
                        break;

                    case Player.STATE_IDLE:
                        mCurrentState = STATE_IDLE;
                        break;
                }
            }

            @Override
            public void onRepeatModeChanged(int repeatMode) {
                Log.e("onRepeatModeChanged", repeatMode + "");
            }

            @Override
            public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
                Log.e("onShuffleM..Changed", shuffleModeEnabled + "");
            }

            @Override
            public void onPlayerError(ExoPlaybackException error) {
                mCurrentState = STATE_ERROR;
                mTargetState = STATE_ERROR;
                if (mMediaController != null) {
                    mMediaController.showError();
                }
                error.printStackTrace();
            }

            @Override
            public void onPositionDiscontinuity(int reason) {
                Log.e("onPositionDiscontinuity", reason + "");
            }

            @Override
            public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
                Log.e("onPlaybackP..Changed", playbackParameters.toString());
            }

            @Override
            public void onSeekProcessed() {
                Log.e("onTracksChanged", "onTracksChanged");
            }
        });
    }

    public void setWantWH(int wantWidth, int wantHeight) {
        this.wantWidth = wantWidth;
        this.wantHeight = wantHeight;
    }

    public int getWantH() {
        return wantHeight;
    }

    public int getWantW() {
        return wantWidth;
    }

    public void readyWithPrepared() {
        mCurrentState = STATE_PREPARED;
        mMediaController.show(0);
        mCanPause = mCanSeekBack = mCanSeekForward = true;

        if (mMediaController != null) {
            mMediaController.hideLoading();
        }

        if (mMediaController != null) {
            mMediaController.setEnabled(true);
        }

        int seekToPosition = mSeekWhenPrepared;  // mSeekWhenPrepared may be changed after seekTo() call
        if (seekToPosition != 0) {
            seekTo(seekToPosition);
        }
        if (mVideoWidth != 0 && mVideoHeight != 0) {
            //Log.i("@@@@", "video size: " + mVideoWidth +"/"+ mVideoHeight);
            getHolder().setFixedSize(mVideoWidth, mVideoHeight);
            if (mSurfaceWidth == mVideoWidth && mSurfaceHeight == mVideoHeight) {
                // We didn't actually change the size (it was already at the size
                // we need), so we won't get a "surface changed" callback, so
                // start the video here instead of in the callback.
                if (mTargetState == STATE_PLAYING) {
//                    Log.e("readyWithPrepared", "start()");
//                    start();
                    if (mMediaController != null) {
                        mMediaController.show();
                    }
                } else if (!isPlaying() &&
                        (seekToPosition != 0 || getCurrentPosition() > 0)) {
                    if (mMediaController != null) {
                        // Show the media controls when we're paused into a video and make 'em stick.
                        mMediaController.show(0);
                    }
                }
            } else {
                if (mTargetState == STATE_PLAYING) {
//                    Log.e("readyWithPrepared", "start()");
//                    start();
                }
            }
        } else {
            // We don't know the video size yet, but should start anyway.
            // The video size might be reported to us later.
            if (mTargetState == STATE_PLAYING) {
//                Log.e("readyWithPrepared", "start()");
//                start();
            }
        }
    }
}
