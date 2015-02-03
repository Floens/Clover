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
package org.floens.chan.ui.fragment;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.floens.chan.R;
import org.floens.chan.chan.ImageSearch;
import org.floens.chan.core.ChanPreferences;
import org.floens.chan.core.model.Post;
import org.floens.chan.ui.activity.ImageViewActivity;
import org.floens.chan.ui.adapter.ImageViewAdapter;
import org.floens.chan.ui.view.ThumbnailImageView;
import org.floens.chan.ui.view.ThumbnailImageView.ThumbnailImageViewCallback;
import org.floens.chan.utils.ImageSaver;
import org.floens.chan.utils.Utils;

import java.io.File;

public class ImageViewFragment extends Fragment implements ThumbnailImageViewCallback {
    private Context context;
    private ImageViewActivity activity;

    private ThumbnailImageView imageView;
    private ConnectivityManager conManager;

    private Post post;
    private boolean showProgressBar = true;
    private boolean isVideo = false;
    private boolean videoVisible = false;
    private boolean videoSetIconToPause = false;
    private boolean tapToLoad = false;
    private boolean loaded = false;

    private long progressCurrent;
    private long progressTotal;
    private boolean progressDone;

    public static ImageViewFragment newInstance(Post post, ImageViewActivity activity, int index) {
        ImageViewFragment imageViewFragment = new ImageViewFragment();
        imageViewFragment.post = post;
        imageViewFragment.activity = activity;
        imageViewFragment.conManager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);

        return imageViewFragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (post == null) {
            // No restoring
            return null;
        } else {
            context = inflater.getContext();

            imageView = new ThumbnailImageView(context);
            imageView.setCallback(this);
            int padding = getResources().getDimensionPixelSize(R.dimen.image_view_padding);
            imageView.setPadding(padding, padding, padding, padding);

            return imageView;
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // No restoring
        if (post != null) {
            if (!post.hasImage) {
                throw new IllegalArgumentException("Post has no image");
            }

            // After layout has been done so getWidth & getHeight don't return 0
            imageView.post(new Runnable() {
                @Override
                public void run() {
                    // When the viewpager is created, it starts loading the first two views,
                    // then we set a position to the viewpager and it loads three more views!
                    // Check for these unused views here to avoid unnecessary loads
                    if (imageView.getWidth() == 0 || imageView.getHeight() == 0)
                        return;

                    imageView.setThumbnail(post.thumbnailUrl);

                    if (ChanPreferences.getImageAutoLoad() && !post.spoiler) {
                        load();
                    } else {
                        tapToLoad = true;
                        showProgressBar(false);

                        if (post.ext.equals("webm")) {
                            isVideo = true;
                            activity.invalidateActionBar();
                        }
                    }
                }
            });
        }
    }

    private void load() {
        if (loaded) return;
        loaded = true;

        switch (post.ext) {
            case "gif":
                imageView.setGif(post.imageUrl);
                break;
            case "webm":
                isVideo = true;
                activity.invalidateActionBar();
                showProgressBar(false);

                if (tapToLoad) {
                    if (!videoVisible) {
                        startVideo();
                    } else {
                        if (imageView.getVideoView() != null) {
                            imageView.getVideoView().start();
                        }
                    }
                }
                break;
            default:
                imageView.setBigImage(post.imageUrl);
                break;
        }

    }

    @Override
    public void onSaveInstanceState(Bundle bundle) {
        // https://code.google.com/p/android/issues/detail?id=19917
        bundle.putString("bug_19917", "bug_19917");
        super.onSaveInstanceState(bundle);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (imageView != null) {
            imageView.cancelLoad();
        }
    }

    public void onSelected(ImageViewAdapter adapter, int position) {
        activity.setProgressBarIndeterminateVisibility(showProgressBar);

        String filename = post.filename + "." + post.ext;
        activity.getSupportActionBar().setTitle(filename);

        String text = (position + 1) + "/" + adapter.getCount();
        activity.getSupportActionBar().setSubtitle(text);

        activity.invalidateActionBar();

        if (isVideo && shouldPlayVideo() && imageView != null) {
            if (!videoVisible) {
                startVideo();
            } else {
                if (imageView.getVideoView() != null) {
                    imageView.getVideoView().start();
                }
            }
        }

        activity.setProgressBar(progressCurrent, progressTotal, progressDone);
    }

    public void onDeselected() {
        if (imageView != null && imageView.getVideoView() != null) {
            imageView.getVideoView().pause();
        }
    }

    public void onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(R.id.action_image_play_state);
        item.setVisible(isVideo);
        item.setEnabled(isVideo);

        if (imageView != null) {
            VideoView view = imageView.getVideoView();
            if (view != null) {
                item.setIcon((videoSetIconToPause || view.isPlaying()) ? R.drawable.ic_action_pause
                        : R.drawable.ic_action_play);
                videoSetIconToPause = false;
            }
        }
    }

    public void customOnOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_image_play_state:
                if (!videoVisible) {
                    startVideo();
                } else {
                    VideoView view = imageView.getVideoView();
                    if (view != null) {
                        if (!view.isPlaying()) {
                            view.start();
                        } else {
                            view.pause();
                        }
                    }
                }

                activity.invalidateActionBar();
                break;
            case R.id.action_open_browser:
                Utils.openLink(context, post.imageUrl);
                break;
            case R.id.action_image_save:
                ImageSaver.getInstance().saveImage(context, post.imageUrl,
                        ChanPreferences.getImageSaveOriginalFilename() ? Long.toString(post.tim) : post.filename, post.ext,
                        item.getItemId() == R.id.action_share);
                break;
            case R.id.action_share:
                if (ChanPreferences.getImageShareUrl()) {
                    shareImageUrl(post.imageUrl);
                } else {
                    ImageSaver.getInstance().saveImage(context, post.imageUrl,
                            ChanPreferences.getImageSaveOriginalFilename() ? Long.toString(post.tim) : post.filename, post.ext,
                            item.getItemId() == R.id.action_share);
                }
                break;
            default:
                // Search if it was an ImageSearch item
                for (ImageSearch engine : ImageSearch.engines) {
                    if (item.getItemId() == engine.getId()) {
                        Utils.openLink(context, engine.getUrl(post.imageUrl));
                        break;
                    }
                }

                break;
        }
    }

    private void shareImageUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, url);
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)));
    }

    public void onVideoError(File video) {
        if (ChanPreferences.getVideoErrorIgnore()) {
            Toast.makeText(context, R.string.image_open_failed, Toast.LENGTH_SHORT).show();
        } else {
            showVideoWarning();
        }
    }

    private void showVideoWarning() {
        LinearLayout notice = new LinearLayout(context);
        notice.setOrientation(LinearLayout.VERTICAL);

        TextView noticeText = new TextView(context);
        noticeText.setText(R.string.video_playback_warning);
        noticeText.setTextSize(16f);
        notice.addView(noticeText, Utils.MATCH_WRAP_PARAMS);

        final CheckBox dontShowAgain = new CheckBox(context);
        dontShowAgain.setText(R.string.video_playback_ignore);
        notice.addView(dontShowAgain, Utils.MATCH_WRAP_PARAMS);

        int padding = Utils.dp(16f);
        notice.setPadding(padding, padding, padding, padding);

        new AlertDialog.Builder(context)
                .setTitle(R.string.video_playback_warning_title)
                .setView(notice)
                .setNeutralButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (dontShowAgain.isChecked()) {
                            ChanPreferences.setVideoErrorIgnore(true);
                        }
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void startVideo() {
        if (videoVisible) return;
        videoVisible = true;

        imageView.setVideo(post.imageUrl);
    }

    public void showProgressBar(boolean e) {
        showProgressBar = e;
        activity.updateActionBarIfSelected(this);
    }

    public boolean shouldPlayVideo() {
        int autoPlaySetting = ChanPreferences.getVideoAutoPlay();

        if(autoPlaySetting == 3) {
            return true;
        } else if(autoPlaySetting != 0) {
            NetworkInfo.State mobile = conManager.getNetworkInfo(0).getState();
            NetworkInfo.State wifi = conManager.getNetworkInfo(1).getState();

            if (mobile == NetworkInfo.State.CONNECTED || mobile == NetworkInfo.State.CONNECTING) {
                return autoPlaySetting == 1;
            } else if (wifi == NetworkInfo.State.CONNECTED || wifi == NetworkInfo.State.CONNECTING) {
                return autoPlaySetting == 2;
            }
        }
        return false;
    }

    @Override
    public void onTap() {
        if (tapToLoad) {
            if (loaded) {
                activity.finish();
            } else {
                load();
            }
        } else {
            activity.finish();
        }
    }

    @Override
    public void setProgress(boolean progress) {
        showProgressBar(progress);
    }

    @Override
    public void setLinearProgress(long current, long total, boolean done) {
        progressCurrent = current;
        progressTotal = total;
        progressDone = done;
        activity.updateActionBarIfSelected(this);
    }

    @Override
    public void onVideoLoaded() {
        videoSetIconToPause = true;
        activity.invalidateActionBar();
    }
}
