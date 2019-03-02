/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.floens.chan.ui.view;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader;
import com.android.volley.toolbox.ImageLoader.ImageContainer;
import com.davemorrissey.labs.subscaleview.ImageSource;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import org.floens.chan.R;
import org.floens.chan.core.cache.FileCache;
import org.floens.chan.core.cache.FileCacheDownloader;
import org.floens.chan.core.cache.FileCacheListener;
import org.floens.chan.core.cache.FileCacheProvider;
import org.floens.chan.core.di.UserAgentProvider;
import org.floens.chan.core.model.PostImage;
import org.floens.chan.core.settings.ChanSettings;
import org.floens.chan.utils.AndroidUtils;
import org.floens.chan.utils.Logger;

import java.io.File;
import java.io.IOException;

import javax.inject.Inject;

import pl.droidsonroids.gif.GifDrawable;
import pl.droidsonroids.gif.GifImageView;

import static org.floens.chan.Chan.inject;

public class MultiImageView extends FrameLayout implements View.OnClickListener {
    public enum Mode {
        UNLOADED, LOWRES, BIGIMAGE, GIF, MOVIE
    }

    private static final String TAG = "MultiImageView";

    @Inject
    FileCache fileCache;

    @Inject
    ImageLoader imageLoader;

    @Inject
    UserAgentProvider userAgent;

    private ImageView playView;

    private PostImage postImage;
    private Callback callback;
    private Mode mode = Mode.UNLOADED;

    private boolean hasContent = false;
    private ImageContainer thumbnailRequest;
    private FileCacheDownloader bigImageRequest;
    private FileCacheDownloader gifRequest;
    private FileCacheDownloader videoRequest;

    private VideoView videoView;
    private PlayerView exoVideoView;
    private boolean videoError = false;
    private MediaPlayer mediaPlayer;
    private SimpleExoPlayer exoPlayer;

    private boolean backgroundToggle;

    public MultiImageView(Context context) {
        this(context, null);
    }

    public MultiImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MultiImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        inject(this);

        setOnClickListener(this);

        playView = new ImageView(getContext());
        playView.setVisibility(View.GONE);
        playView.setImageResource(R.drawable.ic_play_circle_outline_white_48dp);
        addView(playView, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    public void bindPostImage(PostImage postImage, Callback callback) {
        this.postImage = postImage;
        this.callback = callback;

        playView.setVisibility(postImage.type == PostImage.Type.MOVIE ? View.VISIBLE : View.GONE);
    }

    public PostImage getPostImage() {
        return postImage;
    }

    public void setMode(final Mode newMode) {
        if (this.mode != newMode) {
//            Logger.test("Changing mode from " + this.mode + " to " + newMode + " for " + postImage.thumbnailUrl);
            this.mode = newMode;

            AndroidUtils.waitForMeasure(this, new AndroidUtils.OnMeasuredCallback() {
                @Override
                public boolean onMeasured(View view) {
                    switch (newMode) {
                        case LOWRES:
                            setThumbnail(postImage.getThumbnailUrl().toString());
                            break;
                        case BIGIMAGE:
                            setBigImage(postImage.imageUrl.toString());
                            break;
                        case GIF:
                            setGif(postImage.imageUrl.toString());
                            break;
                        case MOVIE:
                            setVideo(postImage.imageUrl.toString());
                            break;
                    }
                    return true;
                }
            });
        }
    }

    public Mode getMode() {
        return mode;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public CustomScaleImageView findScaleImageView() {
        CustomScaleImageView bigImage = null;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof CustomScaleImageView) {
                bigImage = (CustomScaleImageView) getChildAt(i);
            }
        }
        return bigImage;
    }

    public GifImageView findGifImageView() {
        GifImageView gif = null;
        for(int i = 0; i < getChildCount(); i++) {
            if(getChildAt(i) instanceof GifImageView) {
                gif = (GifImageView) getChildAt(i);
            }
        }
        return gif;
    }

    public void setVolume(boolean muted) {
        final float volume = muted ? 0f : 1f;
        if (exoPlayer != null) {
            Player.AudioComponent audioComponent = exoPlayer.getAudioComponent();
            if (audioComponent != null) {
                audioComponent.setVolume(volume);
            }
        } else if (mediaPlayer != null) {
            mediaPlayer.setVolume(volume, volume);
        }
    }

    @Override
    public void onClick(View v) {
        callback.onTap(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelLoad();
    }

    private void setThumbnail(String thumbnailUrl) {
        if (getWidth() == 0 || getHeight() == 0) {
            Logger.e(TAG, "getWidth() or getHeight() returned 0, not loading");
            return;
        }

        if (thumbnailRequest != null) {
            return;
        }

        // Also use volley for the thumbnails
        thumbnailRequest = imageLoader.get(thumbnailUrl, new ImageLoader.ImageListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                thumbnailRequest = null;
                onError();
            }

            @Override
            public void onResponse(ImageContainer response, boolean isImmediate) {
                thumbnailRequest = null;
                if (response.getBitmap() != null && (!hasContent || mode == Mode.LOWRES)) {
                    ImageView thumbnail = new ImageView(getContext());
                    thumbnail.setImageBitmap(response.getBitmap());

                    onModeLoaded(Mode.LOWRES, thumbnail);
                }
            }
        }, getWidth(), getHeight());

        if (thumbnailRequest.getBitmap() != null) {
            // Request was immediate and thumbnailRequest was first set to null in onResponse, and then set to the container
            // when the method returned
            // Still set it to null here
            thumbnailRequest = null;
        }
    }

    private void setBigImage(String imageUrl) {
        if (getWidth() == 0 || getHeight() == 0) {
            Logger.e(TAG, "getWidth() or getHeight() returned 0, not loading big image");
            return;
        }

        if (bigImageRequest != null) {
            return;
        }

        callback.showProgress(this, true);
        bigImageRequest = fileCache.downloadFile(imageUrl, new FileCacheListener() {
            @Override
            public void onProgress(long downloaded, long total) {
                callback.onProgress(MultiImageView.this, downloaded, total);
            }

            @Override
            public void onSuccess(File file) {
                setBigImageFile(file);
            }

            @Override
            public void onFail(boolean notFound) {
                if (notFound) {
                    onNotFoundError();
                } else {
                    onError();
                }
            }

            @Override
            public void onCancel() {
            }

            @Override
            public void onEnd() {
                bigImageRequest = null;
                callback.showProgress(MultiImageView.this, false);
            }
        });
    }

    private void setBigImageFile(File file) {
        setBitImageFileInternal(file, true, Mode.BIGIMAGE);
    }

    private void setGif(String gifUrl) {
        if (getWidth() == 0 || getHeight() == 0) {
            Logger.e(TAG, "getWidth() or getHeight() returned 0, not loading");
            return;
        }

        if (gifRequest != null) {
            return;
        }

        callback.showProgress(this, true);
        gifRequest = fileCache.downloadFile(gifUrl, new FileCacheListener() {
            @Override
            public void onProgress(long downloaded, long total) {
                callback.onProgress(MultiImageView.this, downloaded, total);
            }

            @Override
            public void onSuccess(File file) {
                if (!hasContent || mode == Mode.GIF) {
                    setGifFile(file);
                }
            }

            @Override
            public void onFail(boolean notFound) {
                if (notFound) {
                    onNotFoundError();
                } else {
                    onError();
                }
            }

            @Override
            public void onCancel() {
            }

            @Override
            public void onEnd() {
                gifRequest = null;
                callback.showProgress(MultiImageView.this, false);
            }
        });
    }

    private void setGifFile(File file) {
        GifDrawable drawable;
        try {
            drawable = new GifDrawable(file.getAbsolutePath());

            // For single frame gifs, use the scaling image instead
            // The region decoder doesn't work for gifs, so we unfortunately
            // have to use the more memory intensive non tiling mode.
            if (drawable.getNumberOfFrames() == 1) {
                drawable.recycle();
                setBitImageFileInternal(file, false, Mode.GIF);
                return;
            }
        } catch (IOException e) {
            e.printStackTrace();
            onError();
            return;
        } catch (OutOfMemoryError e) {
            Runtime.getRuntime().gc();
            e.printStackTrace();
            onOutOfMemoryError();
            return;
        }

        GifImageView view = new GifImageView(getContext());
        view.setImageDrawable(drawable);
        onModeLoaded(Mode.GIF, view);
    }

    private void setVideo(String videoUrl) {
        if (videoRequest != null) {
            return;
        }

        callback.showProgress(this, true);
        videoRequest = fileCache.downloadFile(videoUrl, new FileCacheListener() {
            @Override
            public void onProgress(long downloaded, long total) {
                callback.onProgress(MultiImageView.this, downloaded, total);
            }

            @Override
            public void onSuccess(File file) {
                if (!hasContent || mode == Mode.MOVIE) {
                    setVideoFile(file);
                }
            }

            @Override
            public void onFail(boolean notFound) {
                if (notFound) {
                    onNotFoundError();
                } else {
                    onError();
                }
            }

            @Override
            public void onCancel() {
            }

            @Override
            public void onEnd() {
                videoRequest = null;
                callback.showProgress(MultiImageView.this, false);
            }
        });
    }

    private void setVideoFile(final File file) {
        if (ChanSettings.videoOpenExternal.get()) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(FileCacheProvider.getUriForFile(file), "video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            AndroidUtils.openIntent(intent);

            onModeLoaded(Mode.MOVIE, videoView);
        } else if (ChanSettings.videoUseExoplayer.get()) {
            exoVideoView = new PlayerView(getContext());
            exoPlayer = ExoPlayerFactory.newSimpleInstance(getContext());
            exoVideoView.setPlayer(exoPlayer);
            DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(getContext(),
                    Util.getUserAgent(getContext(), userAgent.getUserAgent()));
            MediaSource videoSource = new ExtractorMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(android.net.Uri.fromFile(file));

            exoPlayer.setRepeatMode(ChanSettings.videoAutoLoop.get() ?
                    Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);

            exoPlayer.prepare(videoSource);
            callback.onVideoLoaded(this, hasMediaPlayerAudioTracks(exoPlayer));
            addView(exoVideoView);
            exoPlayer.setPlayWhenReady(true);
        } else {
            Context proxyContext = new NoMusicServiceCommandContext(getContext());

            videoView = new VideoView(proxyContext);
            videoView.setZOrderOnTop(true);
            videoView.setMediaController(new MediaController(getContext()));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                videoView.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
            }

            addView(videoView, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));

            videoView.setOnPreparedListener(mp -> {
                mediaPlayer = mp;
                mp.setLooping(ChanSettings.videoAutoLoop.get());
                mp.setVolume(0f, 0f);
                onModeLoaded(Mode.MOVIE, videoView);
                callback.onVideoLoaded(this, hasMediaPlayerAudioTracks(mp));
            });

            videoView.setOnErrorListener((mp, what, extra) -> {
                onVideoError();

                return true;
            });

            videoView.setVideoPath(file.getAbsolutePath());

            try {
                videoView.start();
            } catch (IllegalStateException e) {
                Logger.e(TAG, "Video view start error", e);
                onVideoError();
            }
        }
    }

    private boolean hasMediaPlayerAudioTracks(MediaPlayer mediaPlayer) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                for (MediaPlayer.TrackInfo trackInfo : mediaPlayer.getTrackInfo()) {
                    if (trackInfo.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                        return true;
                    }
                }

                return false;
            } else {
                // It'll just show the icon without doing anything. Remove when 4.0 is dropped.
                return true;
            }
        } catch (RuntimeException e) {
            // getTrackInfo() raises an IllegalStateException on some devices.
            // Samsung even throws a RuntimeException.
            // Return a default value.
            return true;
        }
    }

    private boolean hasMediaPlayerAudioTracks(ExoPlayer mediaPlayer) {
        return mediaPlayer.getAudioComponent() != null;
    }

    private void onVideoError() {
        if (!videoError) {
            videoError = true;
            callback.onVideoError(this);
        }
    }

    private void cleanupVideo(VideoView videoView) {
        videoView.stopPlayback();
        mediaPlayer = null;
    }

    public void toggleTransparency() {
        final int BACKGROUND_COLOR = Color.argb(255, 211, 217, 241);
        CustomScaleImageView imageView = findScaleImageView();
        GifImageView gifView = findGifImageView();
        if(imageView == null && gifView == null) return;
        boolean isImage = imageView != null && gifView == null;
        if(backgroundToggle) {
            if(isImage) {
                imageView.setTileBackgroundColor(Color.TRANSPARENT);
            } else {
                gifView.getDrawable().setColorFilter(Color.TRANSPARENT, PorterDuff.Mode.DST_OVER);
            }
            backgroundToggle = false;
        } else {
            if(isImage) {
                imageView.setTileBackgroundColor(BACKGROUND_COLOR);
            } else {
                gifView.getDrawable().setColorFilter(BACKGROUND_COLOR, PorterDuff.Mode.DST_OVER);
            }
            backgroundToggle = true;
        }
    }

    private void cleanupVideo(PlayerView videoView) {
        videoView.getPlayer().release();
    }

    private void setBitImageFileInternal(File file, boolean tiling, final Mode forMode) {
        final CustomScaleImageView image = new CustomScaleImageView(getContext());
        image.setImage(ImageSource.uri(file.getAbsolutePath()).tiling(tiling));
        image.setOnClickListener(MultiImageView.this);
        addView(image, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        image.setCallback(new CustomScaleImageView.Callback() {
            @Override
            public void onReady() {
                if (!hasContent || mode == forMode) {
                    callback.showProgress(MultiImageView.this, false);
                    onModeLoaded(Mode.BIGIMAGE, image);
                }
            }

            @Override
            public void onError(boolean wasInitial) {
                onBigImageError(wasInitial);
            }
        });
    }

    private void onError() {
        Toast.makeText(getContext(), R.string.image_preview_failed, Toast.LENGTH_SHORT).show();
        callback.showProgress(this, false);
    }

    private void onNotFoundError() {
        callback.showProgress(this, false);
        Toast.makeText(getContext(), R.string.image_not_found, Toast.LENGTH_SHORT).show();
    }

    private void onOutOfMemoryError() {
        Toast.makeText(getContext(), R.string.image_preview_failed_oom, Toast.LENGTH_SHORT).show();
        callback.showProgress(this, false);
    }

    private void onBigImageError(boolean wasInitial) {
        if (wasInitial) {
            Toast.makeText(getContext(), R.string.image_failed_big_image, Toast.LENGTH_SHORT).show();
            callback.showProgress(this, false);
        }
    }

    private void cancelLoad() {
        if (thumbnailRequest != null) {
            thumbnailRequest.cancelRequest();
            thumbnailRequest = null;
        }
        if (bigImageRequest != null) {
            bigImageRequest.cancel();
            bigImageRequest = null;
        }
        if (gifRequest != null) {
            gifRequest.cancel();
            gifRequest = null;
        }
        if (videoRequest != null) {
            videoRequest.cancel();
            videoRequest = null;
        }
    }

    private void onModeLoaded(Mode mode, View view) {
        if (view != null) {
            // Remove all other views
            boolean alreadyAttached = false;
            for (int i = getChildCount() - 1; i >= 0; i--) {
                View child = getChildAt(i);
                if (child != playView) {
                    if (child != view) {
                        if (child instanceof VideoView) {
                            cleanupVideo((VideoView) child);
                        } else if (child instanceof PlayerView) {
                            cleanupVideo((PlayerView) child);
                        }

                        removeViewAt(i);
                    } else {
                        alreadyAttached = true;
                    }
                }
            }

            if (!alreadyAttached) {
                addView(view, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
        }

        hasContent = true;
        callback.onModeLoaded(this, mode);
    }

    public interface Callback {
        void onTap(MultiImageView multiImageView);

        void showProgress(MultiImageView multiImageView, boolean progress);

        void onProgress(MultiImageView multiImageView, long current, long total);

        void onVideoError(MultiImageView multiImageView);

        void onVideoLoaded(MultiImageView multiImageView, boolean hasAudio);

        void onModeLoaded(MultiImageView multiImageView, Mode mode);
    }

    public static class NoMusicServiceCommandContext extends ContextWrapper {
        public NoMusicServiceCommandContext(Context base) {
            super(base);
        }

        @Override
        public void sendBroadcast(Intent intent) {
            // Only allow broadcasts when it's not a music service command
            // Prevents pause intents from broadcasting
            if (!"com.android.music.musicservicecommand".equals(intent.getAction())) {
                super.sendBroadcast(intent);
            }
        }
    }
}
