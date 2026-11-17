/*
 * Copyright 2026 ThreadForge
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

package com.threadforge.chat.web;

import com.threadforge.chat.security.CurrentUser;
import com.threadforge.chat.security.RateLimitExceededException;
import com.threadforge.chat.security.RateLimiter;
import com.threadforge.chat.service.ChannelService;
import com.threadforge.chat.service.ConversationService;
import com.threadforge.chat.service.SearchService;
import com.threadforge.chat.web.dto.SearchHitDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;
import java.util.List;

/**
 * {@code GET /api/search}. Four modes, picked by the request parameters:
 *
 * <ul>
 *   <li>{@code ?channelId=} — that one channel (public, or one the viewer is in).</li>
 *   <li>{@code ?conversationId=} — that one DM / group conversation; members only.</li>
 *   <li>{@code ?scope=all} — every channel including private ones the admin never joined, and no
 *       conversations; admins only.</li>
 *   <li>neither — everything the viewer can read: every public channel ∪ the private ones they
 *       joined ∪ their conversations, ranked as one list. This is the search dropdown.</li>
 * </ul>
 *
 * <p>This endpoint serves the live dropdown: a short, fast page for "jump straight to the thing I
 * remember". The reviewable result set — count, scannable list, pagination — is the server-rendered
 * {@code /search} page ({@link SearchPageController}). Both go through
 * {@link SearchService#searchPage} and {@link SearchHitAssembler}, so a row means the same thing
 * whichever surface drew it.
 *
 * <p>Access control lives in {@link SearchService} / the Lucene query, not here.
 */
@RestController
@RequestMapping("/api/search")
public class SearchRestController {

    private final SearchService searchService;
    private final ChannelService channelService;
    private final ConversationService conversationService;
    private final CurrentUser currentUser;
    private final SearchHitAssembler assembler;
    private final RateLimiter rateLimiter;

    public SearchRestController(SearchService searchService,
                                ChannelService channelService,
                                ConversationService conversationService,
                                CurrentUser currentUser,
                                SearchHitAssembler assembler,
                                RateLimiter rateLimiter) {
        this.searchService = searchService;
        this.channelService = channelService;
        this.conversationService = conversationService;
        this.currentUser = currentUser;
        this.assembler = assembler;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public List<SearchHitDto> search(@RequestParam("q") String q,
                                     @RequestParam(value = "channelId", required = false) Long channelId,
                                     @RequestParam(value = "conversationId", required = false) Long conversationId,
                                     @RequestParam(value = "scope", required = false) String scope,
                                     @RequestParam(defaultValue = "50") int limit,
                                     Principal principal) {
        var me = currentUser.resolve(principal);
        // 30 searches/min/user. Lucene fuzzy queries on long terms are nontrivial CPU even
        // after the parser-level guards (no wildcards, edits capped at 2); a tight loop of
        // expensive queries used to be a free DoS vector, this caps the burst.
        if (!rateLimiter.tryAcquire(me.getUsername(), "search", 30, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("search rate exceeded");
        }
        var channel = channelId == null ? null : channelService.requireById(channelId);
        var conversation = conversationId == null ? null : conversationService.requireById(conversationId);
        var kind = SearchScopes.resolve(scope, channel, conversation);
        var page = searchService.searchPage(me, q, kind, channel, conversation, 0, limit);
        return assembler.assemble(me, page.hits(), q);
    }
}
