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
package org.floens.chan.core.site.parser;

import android.graphics.Typeface;
import android.support.annotation.AnyThread;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import org.floens.chan.core.model.Post;
import org.floens.chan.core.model.PostLinkable;
import org.floens.chan.core.site.Page;
import org.floens.chan.ui.span.AbsoluteSizeSpanHashed;
import org.floens.chan.ui.span.ForegroundColorSpanHashed;
import org.floens.chan.ui.theme.Theme;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.floens.chan.core.site.parser.StyleRule.tagRule;
import static org.floens.chan.utils.AndroidUtils.sp;

@AnyThread
public class CommentParser {
    private static final String SAVED_REPLY_SUFFIX = " (You)";
    private static final String OP_REPLY_SUFFIX = " (OP)";
    private static final String EXTERN_THREAD_LINK_SUFFIX = " \u2192"; // arrow to the right

    private Pattern fullQuotePattern = Pattern.compile("/(\\w+)/\\w+/(\\d+)#p(\\d+)");
    private Pattern quotePattern = Pattern.compile(".*#p(\\d+)");
    private Pattern boardLinkPattern = Pattern.compile("//boards\\.4chan.*?\\.org/(.*?)/");
    private Pattern boardSearchPattern = Pattern.compile("//boards\\.4chan.*?\\.org/(.*?)/catalog#s=(.*)");
    private Pattern colorPattern = Pattern.compile("color:#([0-9a-fA-F]+)");

    private Map<String, List<StyleRule>> rules = new HashMap<>();

    public CommentParser() {
        // Required tags.
        rule(tagRule("p"));
        rule(tagRule("div"));
        rule(tagRule("br").just("\n"));
    }

    public void addDefaultRules() {
        rule(tagRule("a").action(this::handleAnchor));

        rule(tagRule("span").cssClass("deadlink").color(StyleRule.Color.QUOTE).strikeThrough());
        rule(tagRule("span").cssClass("spoiler").link(PostLinkable.Type.SPOILER));
        rule(tagRule("span").cssClass("fortune").action(this::handleFortune));
        rule(tagRule("span").cssClass("abbr").nullify());
        rule(tagRule("span").color(StyleRule.Color.INLINE_QUOTE).linkify());

        rule(tagRule("table").action(this::handleTable));

        rule(tagRule("s").link(PostLinkable.Type.SPOILER));

        rule(tagRule("strong").bold());
        rule(tagRule("b").bold());

        rule(tagRule("i").italic());
        rule(tagRule("em").italic());

        rule(tagRule("pre").cssClass("prettyprint").monospace().size(sp(12f)));
    }

    public void rule(StyleRule rule) {
        List<StyleRule> list = rules.get(rule.tag());
        if (list == null) {
            list = new ArrayList<>(3);
            rules.put(rule.tag(), list);
        }

        list.add(rule);
    }

    public void setQuotePattern(Pattern quotePattern) {
        this.quotePattern = quotePattern;
    }

    public void setFullQuotePattern(Pattern fullQuotePattern) {
        this.fullQuotePattern = fullQuotePattern;
    }

    public CharSequence handleTag(PostParser.Callback callback,
                                  Theme theme,
                                  Post.Builder post,
                                  String tag,
                                  CharSequence text,
                                  Element element) {

        List<StyleRule> rules = this.rules.get(tag);
        if (rules != null) {
            for (int i = 0; i < 2; i++) {
                boolean highPriority = i == 0;
                for (StyleRule rule : rules) {
                    if (rule.highPriority() == highPriority && rule.applies(element)) {
                        return rule.apply(theme, callback, post, text, element);
                    }
                }
            }
        }

        switch (tag) {
            default:
                // Unknown tag, return the text;
                return text;
        }
    }

    private CharSequence appendBreakIfNotLastSibling(CharSequence text, Element element) {
        if (element.nextSibling() != null) {
            return TextUtils.concat(text, "\n");
        } else {
            return text;
        }
    }

    private CharSequence handleAnchor(Theme theme,
                                      PostParser.Callback callback,
                                      Post.Builder post,
                                      CharSequence text,
                                      Element anchor) {
        CommentParser.Link handlerLink = matchAnchor(post, text, anchor, callback);

        if (handlerLink != null) {
            if (handlerLink.type == PostLinkable.Type.THREAD) {
                handlerLink.key = TextUtils.concat(handlerLink.key, EXTERN_THREAD_LINK_SUFFIX);
            }

            if (handlerLink.type == PostLinkable.Type.QUOTE) {
                int postNo = (int) handlerLink.value;
                post.addReplyTo(postNo);

                // Append (OP) when it's a reply to OP
                if (postNo == post.opId) {
                    handlerLink.key = TextUtils.concat(handlerLink.key, OP_REPLY_SUFFIX);
                }

                // Append (You) when it's a reply to an saved reply
                if (callback.isSaved(postNo)) {
                    handlerLink.key = TextUtils.concat(handlerLink.key, SAVED_REPLY_SUFFIX);
                }
            }

            SpannableString res = new SpannableString(handlerLink.key);
            PostLinkable pl = new PostLinkable(theme, handlerLink.key, handlerLink.value, handlerLink.type);
            res.setSpan(pl, 0, res.length(), 0);
            post.addLinkable(pl);

            return res;
        } else {
            return null;
        }
    }

    private CharSequence handleFortune(Theme theme,
                                       PostParser.Callback callback,
                                       Post.Builder builder,
                                       CharSequence text,
                                       Element span) {
        // html looks like <span class="fortune" style="color:#0893e1"><br><br><b>Your fortune:</b>
        String style = span.attr("style");
        if (!TextUtils.isEmpty(style)) {
            style = style.replace(" ", "");

            Matcher matcher = colorPattern.matcher(style);
            if (matcher.find()) {
                int hexColor = Integer.parseInt(matcher.group(1), 16);
                if (hexColor >= 0 && hexColor <= 0xffffff) {
                    text = span(text, new ForegroundColorSpanHashed(0xff000000 + hexColor),
                            new StyleSpan(Typeface.BOLD));
                }
            }
        }

        return text;
    }

    public CharSequence handleTable(Theme theme,
                                    PostParser.Callback callback,
                                    Post.Builder builder,
                                    CharSequence text,
                                    Element table) {
        List<CharSequence> parts = new ArrayList<>();
        Elements tableRows = table.getElementsByTag("tr");
        for (int i = 0; i < tableRows.size(); i++) {
            Element tableRow = tableRows.get(i);
            if (tableRow.text().length() > 0) {
                Elements tableDatas = tableRow.getElementsByTag("td");
                for (int j = 0; j < tableDatas.size(); j++) {
                    Element tableData = tableDatas.get(j);

                    SpannableString tableDataPart = new SpannableString(tableData.text());
                    if (tableData.getElementsByTag("b").size() > 0) {
                        tableDataPart.setSpan(new StyleSpan(Typeface.BOLD), 0, tableDataPart.length(), 0);
                        tableDataPart.setSpan(new UnderlineSpan(), 0, tableDataPart.length(), 0);
                    }

                    parts.add(tableDataPart);

                    if (j < tableDatas.size() - 1) parts.add(": ");
                }

                if (i < tableRows.size() - 1) parts.add("\n");
            }
        }

        // Overrides the text (possibly) parsed by child nodes.
        return span(TextUtils.concat(parts.toArray(new CharSequence[parts.size()])),
                new ForegroundColorSpanHashed(theme.inlineQuoteColor),
                new AbsoluteSizeSpanHashed(sp(12f)));
    }

    public Link matchAnchor(Post.Builder post, CharSequence text, Element anchor, PostParser.Callback callback) {
        String href = anchor.attr("href");
        // gets us something like /board/thread/postno#quoteno
        //hacky fix for 4chan having two domains but the same API
        if(href.matches("//boards\\.4chan.*?\\.org/(.*?)/thread/(\\d*?)#p(\\d*)")) {
            href = href.substring(2);
            href = href.substring(href.indexOf('/'));
        }

        PostLinkable.Type t;
        Object value;

        Matcher externalMatcher = fullQuotePattern.matcher(href);
        if (externalMatcher.matches()) {
            String board = externalMatcher.group(1);
            int threadId = Integer.parseInt(externalMatcher.group(2));
            int postId = Integer.parseInt(externalMatcher.group(3));

            if (board.equals(post.board.code) && callback.isInternal(postId)) {
                //link to post in same thread with post number (>>post)
                t = PostLinkable.Type.QUOTE;
                value = postId;
            } else {
                //link to post not in same thread with post number (>>post or >>>/board/post)
                t = PostLinkable.Type.THREAD;
                value = new PostLinkable.ThreadLink(board, threadId, postId);
            }
        } else {
            Matcher quoteMatcher = quotePattern.matcher(href);
            if (quoteMatcher.matches()) {
                //link to post backup???
                t = PostLinkable.Type.QUOTE;
                value = Integer.parseInt(quoteMatcher.group(1));
            } else {
                Matcher boardLinkMatcher = boardLinkPattern.matcher(href);
                Matcher boardSearchMatcher = boardSearchPattern.matcher(href);
                if(boardLinkMatcher.matches()) {
                    //board link
                    t = PostLinkable.Type.BOARD;
                    value = boardLinkMatcher.group(1);
                } else if (boardSearchMatcher.matches()) {
                    //search link
                    String board = boardSearchMatcher.group(1);
                    String search = boardSearchMatcher.group(2);
                    t = PostLinkable.Type.SEARCH;
                    value = new PostLinkable.SearchLink(board, search);
                } else {
                    //normal link
                    t = PostLinkable.Type.LINK;
                    value = href;
                }
            }
        }

        Link link = new Link();
        link.type = t;
        link.key = text;
        link.value = value;
        return link;
    }

    public SpannableString span(CharSequence text, Object... additionalSpans) {
        SpannableString result = new SpannableString(text);
        int l = result.length();

        if (additionalSpans != null && additionalSpans.length > 0) {
            for (Object additionalSpan : additionalSpans) {
                if (additionalSpan != null) {
                    result.setSpan(additionalSpan, 0, l, 0);
                }
            }
        }

        return result;
    }

    public class Link {
        public PostLinkable.Type type;
        public CharSequence key;
        public Object value;
    }
}
