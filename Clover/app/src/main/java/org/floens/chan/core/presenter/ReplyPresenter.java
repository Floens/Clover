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
package org.floens.chan.core.presenter;

import android.text.TextUtils;

import org.floens.chan.R;
import org.floens.chan.core.database.DatabaseManager;
import org.floens.chan.core.manager.ReplyManager;
import org.floens.chan.core.manager.WatchManager;
import org.floens.chan.core.model.ChanThread;
import org.floens.chan.core.model.Post;
import org.floens.chan.core.model.orm.Board;
import org.floens.chan.core.model.orm.Loadable;
import org.floens.chan.core.model.orm.SavedReply;
import org.floens.chan.core.settings.ChanSettings;
import org.floens.chan.core.site.Site;
import org.floens.chan.core.site.SiteActions;
import org.floens.chan.core.site.SiteAuthentication;
import org.floens.chan.core.site.http.HttpCall;
import org.floens.chan.core.site.http.Reply;
import org.floens.chan.core.site.http.ReplyResponse;
import org.floens.chan.ui.captcha.AuthenticationLayoutCallback;
import org.floens.chan.ui.captcha.AuthenticationLayoutInterface;
import org.floens.chan.ui.helper.ImagePickDelegate;
import org.floens.chan.utils.AndroidUtils;
import org.floens.chan.utils.Logger;

import java.io.File;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import static org.floens.chan.utils.AndroidUtils.getAppContext;
import static org.floens.chan.utils.AndroidUtils.getReadableFileSize;
import static org.floens.chan.utils.AndroidUtils.getRes;
import static org.floens.chan.utils.AndroidUtils.getString;

public class ReplyPresenter implements AuthenticationLayoutCallback, ImagePickDelegate.ImagePickCallback, SiteActions.PostListener {
    public enum Page {
        INPUT,
        AUTHENTICATION,
        LOADING
    }

    private static final String TAG = "ReplyPresenter";
    private static final Pattern QUOTE_PATTERN = Pattern.compile(">>\\d+");
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private ReplyPresenterCallback callback;

    private ReplyManager replyManager;
    private WatchManager watchManager;
    private DatabaseManager databaseManager;

    private boolean bound = false;
    private Loadable loadable;
    private Board board;
    private Reply draft;

    private Page page = Page.INPUT;
    private boolean moreOpen;
    private boolean previewOpen;
    private boolean pickingFile;
    private int selectedQuote = -1;

    @Inject
    public ReplyPresenter(ReplyManager replyManager,
                          WatchManager watchManager,
                          DatabaseManager databaseManager) {
        this.replyManager = replyManager;
        this.watchManager = watchManager;
        this.databaseManager = databaseManager;
    }

    public void create(ReplyPresenterCallback callback) {
        this.callback = callback;
    }

    public void bindLoadable(Loadable loadable) {
        if (this.loadable != null) {
            unbindLoadable();
        }
        bound = true;
        this.loadable = loadable;

        this.board = loadable.board;

        draft = replyManager.getReply(loadable);

        if (TextUtils.isEmpty(draft.name)) {
            draft.name = ChanSettings.postDefaultName.get();
        }

        callback.loadDraftIntoViews(draft);
        callback.updateCommentCount(0, board.maxCommentChars, false);
        callback.setCommentHint(getString(loadable.isThreadMode() ? R.string.reply_comment_thread : R.string.reply_comment_board));
        callback.showCommentCounter(board.maxCommentChars > 0);

        if (draft.file != null) {
            showPreview(draft.fileName, draft.file);
        }

        switchPage(Page.INPUT, false);
    }

    public void unbindLoadable() {
        bound = false;
        draft.file = null;
        draft.fileName = "";
        callback.loadViewsIntoDraft(draft);
        replyManager.putReply(loadable, draft);

        closeAll();
    }

    public void onOpen(boolean open) {
        if (open) {
            callback.focusComment();
        }
    }

    public boolean onBack() {
        if (page == Page.LOADING) {
            return true;
        } else if (page == Page.AUTHENTICATION) {
            switchPage(Page.INPUT, true);
            return true;
        } else if (moreOpen) {
            onMoreClicked();
            return true;
        }
        return false;
    }

    public void onMoreClicked() {
        moreOpen = !moreOpen;
        callback.setExpanded(moreOpen);
        callback.openNameOptions(moreOpen);
        if (!loadable.isThreadMode()) {
            callback.openSubject(moreOpen);
        }
        callback.openCommentQuoteButton(moreOpen);
        if (board.spoilers) {
            callback.openCommentSpoilerButton(moreOpen);
        }
        if (previewOpen) {
            callback.openFileName(moreOpen);
            if (board.spoilers) {
                callback.openSpoiler(moreOpen, false);
            }
        }
    }

    public boolean isExpanded() {
        return moreOpen;
    }

    public void onAttachClicked() {
        if (!pickingFile) {
            if (previewOpen) {
                callback.openPreview(false, null);
                draft.file = null;
                draft.fileName = "";
                if (moreOpen) {
                    callback.openFileName(false);
                    if (board.spoilers) {
                        callback.openSpoiler(false, false);
                    }
                }
                previewOpen = false;
            } else {
                callback.getImagePickDelegate().pick(this);
                pickingFile = true;
            }
        }
    }

    public void onSubmitClicked() {
        callback.loadViewsIntoDraft(draft);
        draft.loadable = loadable;

        draft.spoilerImage = draft.spoilerImage && board.spoilers;

        draft.captchaResponse = null;
        if (loadable.site.actions().postRequiresAuthentication()) {
            switchPage(Page.AUTHENTICATION, true);
        } else {
            makeSubmitCall();
        }
    }

    @Override
    public void onPostComplete(HttpCall httpCall, ReplyResponse replyResponse) {
        if (replyResponse.posted) {
            if (ChanSettings.postPinThread.get()) {
                if (loadable.isThreadMode()) {
                    ChanThread thread = callback.getThread();
                    if (thread != null) {
                        watchManager.createPin(loadable, thread.op);
                    }
                } else {
                    Loadable postedLoadable = databaseManager.getDatabaseLoadableManager()
                            .get(Loadable.forThread(loadable.site, loadable.board,
                                    replyResponse.postNo));

                    watchManager.createPin(postedLoadable);
                }
            }

            SavedReply savedReply = SavedReply.fromSiteBoardNoPassword(
                    loadable.site, loadable.board, replyResponse.postNo, replyResponse.password);
            databaseManager.runTaskAsync(databaseManager.getDatabaseSavedReplyManager()
                    .saveReply(savedReply));

            switchPage(Page.INPUT, false);
            closeAll();
            highlightQuotes();
            String name = draft.name;
            draft = new Reply();
            draft.name = name;
            replyManager.putReply(loadable, draft);
            callback.loadDraftIntoViews(draft);
            callback.onPosted();

            if (bound && !loadable.isThreadMode()) {
                callback.showThread(databaseManager.getDatabaseLoadableManager().get(
                        Loadable.forThread(loadable.site, loadable.board, replyResponse.postNo)));
            }
        } else if (replyResponse.requireAuthentication) {
            switchPage(Page.AUTHENTICATION, true);
        } else {
            String errorMessage = getString(R.string.reply_error);
            if (replyResponse.errorMessage != null) {
                errorMessage = getAppContext().getString(
                        R.string.reply_error_message, replyResponse.errorMessage);
            }

            Logger.e(TAG, "onPostComplete error", errorMessage);
            switchPage(Page.INPUT, true);
            callback.openMessage(true, false, errorMessage, true);
        }
    }

    @Override
    public void onUploadingProgress(int percent) {
        //called on a background thread!

        AndroidUtils.runOnUiThread(() -> callback.onUploadingProgress(percent));
    }

    @Override
    public void onPostError(HttpCall httpCall, Exception exception) {
        Logger.e(TAG, "onPostError", exception);

        switchPage(Page.INPUT, true);

        String errorMessage = getString(R.string.reply_error);
        if (exception != null) {
            String message = exception.getMessage();
            if (message != null) {
                errorMessage = getAppContext().getString(R.string.reply_error_message, message);
            }
        }

        callback.openMessage(true, false, errorMessage, true);
    }

    @Override
    public void onAuthenticationComplete(AuthenticationLayoutInterface authenticationLayout, String challenge, String response) {
        draft.captchaChallenge = challenge;
        draft.captchaResponse = response;
        authenticationLayout.reset();
        makeSubmitCall();
    }

    public void onCommentTextChanged(CharSequence text) {
        int length = text.toString().getBytes(UTF_8).length;
        callback.updateCommentCount(length, board.maxCommentChars, length > board.maxCommentChars);
    }

    public void onSelectionChanged() {
        callback.loadViewsIntoDraft(draft);
        highlightQuotes();
    }

    public void commentQuoteClicked() {
        commentInsert(">");
    }

    public void commentSpoilerClicked() {
        commentInsert("[spoiler]", "[/spoiler]");
    }

    public void quote(Post post, boolean withText) {
        handleQuote(post, withText ? post.comment.toString() : null);
    }

    public void quote(Post post, CharSequence text) {
        handleQuote(post, text.toString());
    }

    private void handleQuote(Post post, String textQuote) {
        callback.loadViewsIntoDraft(draft);

        String extraNewline = "";
        if (draft.selection - 1 >= 0 && draft.selection - 1 < draft.comment.length() &&
                draft.comment.charAt(draft.selection - 1) != '\n') {
            extraNewline = "\n";
        }

        String postQuote = "";
        if (post != null) {
            if (!draft.comment.contains(">>" + post.no)) {
                postQuote = ">>" + post.no + "\n";
            }
        }

        StringBuilder textQuoteResult = new StringBuilder();
        if (textQuote != null) {
            String[] lines = textQuote.split("\n+");
            // matches for >>123, >>123 (OP), >>123 (You), >>>/fit/123
            final Pattern quotePattern = Pattern.compile("^>>(>/[a-z0-9]+/)?\\d+.*$");
            for (String line : lines) {
                // do not include post no from quoted post
                if (!quotePattern.matcher(line).matches()) {
                    textQuoteResult.append(">").append(line).append("\n");
                }
            }
        }

        commentInsert(extraNewline + postQuote + textQuoteResult.toString());

        highlightQuotes();
    }

    private void commentInsert(String insertBefore) {
        commentInsert(insertBefore, "");
    }

    private void commentInsert(String insertBefore, String insertAfter) {
        draft.comment = new StringBuilder(draft.comment)
                .insert(draft.selection, insertBefore + insertAfter).toString();
        draft.selection += insertBefore.length();
        callback.loadDraftIntoViews(draft);
    }

    @Override
    public void onFilePickLoading() {
        callback.onFilePickLoading();
    }

    @Override
    public void onFilePicked(String name, File file) {
        pickingFile = false;
        draft.file = file;
        draft.fileName = name;
        showPreview(name, file);
    }

    @Override
    public void onFilePickError(boolean cancelled) {
        pickingFile = false;
        if (!cancelled) {
            callback.onFilePickError();
        }
    }

    private void closeAll() {
        moreOpen = false;
        previewOpen = false;
        selectedQuote = -1;
        callback.openMessage(false, true, "", false);
        callback.setExpanded(false);
        callback.openSubject(false);
        callback.openCommentQuoteButton(false);
        callback.openCommentSpoilerButton(false);
        callback.openNameOptions(false);
        callback.openFileName(false);
        callback.openSpoiler(false, false);
        callback.openPreview(false, null);
        callback.openPreviewMessage(false, null);
    }

    private void makeSubmitCall() {
        loadable.getSite().actions().post(draft, this);
        switchPage(Page.LOADING, true);
    }

    private void switchPage(Page page, boolean animate) {
        if (this.page != page) {
            this.page = page;
            switch (page) {
                case LOADING:
                    callback.setPage(Page.LOADING, true);
                    break;
                case INPUT:
                    callback.setPage(Page.INPUT, animate);
                    break;
                case AUTHENTICATION:
                    SiteAuthentication authentication = loadable.site.actions().postAuthenticate();

                    callback.initializeAuthentication(loadable.site, authentication, this);
                    callback.setPage(Page.AUTHENTICATION, true);

                    break;
            }
        }
    }

    private void highlightQuotes() {
        Matcher matcher = QUOTE_PATTERN.matcher(draft.comment);

        // Find all occurrences of >>\d+ with start and end between selection
        int no = -1;
        while (matcher.find()) {
            if (matcher.start() <= draft.selection && matcher.end() >= draft.selection - 1) {
                String quote = matcher.group().substring(2);
                try {
                    no = Integer.parseInt(quote);
                    break;
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // Allow no = -1 removing the highlight
        if (no != selectedQuote) {
            selectedQuote = no;
            callback.highlightPostNo(no);
        }
    }

    private void showPreview(String name, File file) {
        callback.openPreview(true, file);
        if (moreOpen) {
            callback.openFileName(true);
            if (board.spoilers) {
                callback.openSpoiler(true, false);
            }
        }
        callback.setFileName(name);
        previewOpen = true;

        boolean probablyWebm = file.getName().endsWith(".webm");
        int maxSize = probablyWebm ? board.maxWebmSize : board.maxFileSize;
        if (file.length() > maxSize) {
            String fileSize = getReadableFileSize(file.length(), false);
            String maxSizeString = getReadableFileSize(maxSize, false);
            String text = getRes().getString(probablyWebm ? R.string.reply_webm_too_big : R.string.reply_file_too_big, fileSize, maxSizeString);
            callback.openPreviewMessage(true, text);
        } else {
            callback.openPreviewMessage(false, null);
        }
    }

    public interface ReplyPresenterCallback {
        void loadViewsIntoDraft(Reply draft);

        void loadDraftIntoViews(Reply draft);

        void setPage(Page page, boolean animate);

        void initializeAuthentication(Site site, SiteAuthentication authentication,
                                      AuthenticationLayoutCallback callback);

        void resetAuthentication();

        void openMessage(boolean open, boolean animate, String message, boolean autoHide);

        void onPosted();

        void setCommentHint(String hint);

        void showCommentCounter(boolean show);

        void setExpanded(boolean expanded);

        void openNameOptions(boolean open);

        void openSubject(boolean open);

        void openCommentQuoteButton(boolean open);

        void openCommentSpoilerButton(boolean open);

        void openFileName(boolean open);

        void setFileName(String fileName);

        void updateCommentCount(int count, int maxCount, boolean over);

        void openPreview(boolean show, File previewFile);

        void openPreviewMessage(boolean show, String message);

        void openSpoiler(boolean show, boolean checked);

        void onFilePickLoading();

        void onFilePickError();

        void highlightPostNo(int no);

        void showThread(Loadable loadable);

        ImagePickDelegate getImagePickDelegate();

        ChanThread getThread();

        void focusComment();

        void onUploadingProgress(int percent);
    }
}
