/*
 * Kuroba - *chan browser https://github.com/Adamantcheese/Kuroba/
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
package com.github.adamantcheese.chan.core.site.common.taimaba;

import com.github.adamantcheese.chan.core.model.Post;
import com.github.adamantcheese.chan.core.model.orm.Board;
import com.github.adamantcheese.chan.core.model.orm.Loadable;
import com.github.adamantcheese.chan.core.site.common.CommonSite;

import java.util.Locale;
import java.util.Map;

import okhttp3.HttpUrl;

public class TaimabaEndpoints extends CommonSite.CommonEndpoints {
    protected final CommonSite.SimpleHttpUrl root;
    protected final CommonSite.SimpleHttpUrl sys;
    protected final CommonSite.SimpleHttpUrl swfthumb;
    private final HttpUrl report = new HttpUrl.Builder().scheme("https").host("cdn.420chan.org").port(8443).build();

    public TaimabaEndpoints(CommonSite commonSite, String rootUrl, String sysUrl) {
        super(commonSite);
        root = new CommonSite.SimpleHttpUrl(rootUrl);
        sys = new CommonSite.SimpleHttpUrl(sysUrl);
        swfthumb = new CommonSite.SimpleHttpUrl("https://abload.de/img/swf5dklf.jpg");
    }

    @Override
    public HttpUrl catalog(Board board) {
        return root.builder().s(board.code).s("catalog.json").url();
    }

    @Override
    public HttpUrl boards() {
        return root.builder().s("boards.json").url();
    }

    @Override
    public HttpUrl thread(Board board, Loadable loadable) {
        return root.builder().s(board.code).s("res").s(loadable.no + ".json").url();
    }

    @Override
    public HttpUrl thumbnailUrl(Post.Builder post, boolean spoiler, Map<String, String> arg) {
        switch(arg.get("ext")) {
            case "swf":
                return swfthumb.builder().url();
            default:
                return sys.builder().s(post.board.code).s("thumb").s(arg.get("tim") + "s.jpg").url();
        }
    }

    @Override
    public HttpUrl imageUrl(Post.Builder post, Map<String, String> arg) {
        return sys.builder().s(post.board.code).s("src").s(arg.get("tim") + "." + arg.get("ext")).url();
    }

    @Override
    public HttpUrl icon(String icon, Map<String, String> arg) {
        CommonSite.SimpleHttpUrl stat = sys.builder().s("static");

        if (icon.equals("country")) {
            stat.s("flags").s(arg.get("country_code").toLowerCase(Locale.ENGLISH) + ".png");
        }

        return stat.url();
    }

    @Override
    public HttpUrl pages(Board board) {
        return root.builder().s(board.code).s("threads.json").url();
    }

    @Override
    public HttpUrl reply(Loadable loadable) {
        return sys.builder().s(loadable.board.code).s("taimaba.pl").url();
    }

    @Override
    public HttpUrl report(Post post) {
        return report.newBuilder()
            .addPathSegment("narcbot")
            .addPathSegment("ajaxReport.jsp")
            .addQueryParameter("postId", String.valueOf(post.no))
            .addQueryParameter("reason", "RULE_VIOLATION")
            .addQueryParameter("note", "Kuroba generated report")
            .addQueryParameter("location", "http://boards.420chan.org/" + post.board.code + "/" + String.valueOf(post.no))
            .build();
    }
}
