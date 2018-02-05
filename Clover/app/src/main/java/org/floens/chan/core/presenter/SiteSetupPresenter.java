package org.floens.chan.core.presenter;

import org.floens.chan.core.database.DatabaseManager;
import org.floens.chan.core.settings.Setting;
import org.floens.chan.core.site.Site;

import java.util.List;

import javax.inject.Inject;

public class SiteSetupPresenter {
    private Callback callback;
    private Site site;
    private DatabaseManager databaseManager;
    private boolean hasLogin;

    @Inject
    public SiteSetupPresenter(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void create(Callback callback, Site site) {
        this.callback = callback;
        this.site = site;

        hasLogin = site.feature(Site.Feature.LOGIN);

        if (hasLogin) {
            callback.showLogin();
        }

        List<Setting<?>> settings = site.settings();
        if (!settings.isEmpty()) {
            callback.showSettings(settings);
        }
    }

    public void show() {
        setBoardCount(callback, site);
        if (hasLogin) {
            callback.setIsLoggedIn(site.isLoggedIn());
        }
    }

    private void setBoardCount(Callback callback, Site site) {
        callback.setBoardCount(
                databaseManager.runTask(
                        databaseManager.getDatabaseBoardManager().getSiteSavedBoards(site)
                ).size()
        );
    }

    public interface Callback {
        void setBoardCount(int boardCount);

        void showLogin();

        void setIsLoggedIn(boolean isLoggedIn);

        void showSettings(List<Setting<?>> settings);
    }
}
