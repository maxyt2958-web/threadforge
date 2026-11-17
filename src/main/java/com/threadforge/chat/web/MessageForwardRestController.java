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
import com.threadforge.chat.security.PublicBadRequestException;
import com.threadforge.chat.security.RateLimitExceededException;
import com.threadforge.chat.security.RateLimiter;
import com.threadforge.chat.service.ChannelService;
import com.threadforge.chat.service.ConversationService;
import com.threadforge.chat.service.MarkdownRenderer;
import com.threadforge.chat.service.MessageForwardService;
import com.threadforge.chat.web.dto.ConversationMessageDto;
import com.threadforge.chat.web.dto.ForwardMessageRequest;
import com.threadforge.chat.web.dto.MessageDto;
import com.threadforge.chat.web.dto.MessageEvent;
import jakarta.validation.Valid;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Forwarding a message into another channel or a conversation.
 *
 * <p>Its own controller rather than another method on {@code MessageRestController}, because it is
 * the one endpoint that writes to a room other than the one the message is in and therefore has to
 * broadcast on a topic it was not given: the response goes back to the forwarder, and the new
 * message goes out on the destination's topic, which may be a channel or a conversation. Keeping
 * that fan-out in one small class is better than teaching either room's controller about the other.
 *
 * <p>See {@code MessageForwardService} for the rules — the authorisation on both ends, why the
 * forwarded copy quotes rather than impersonates, why a private source needs an acknowledgement,
 * and why a conversation is a destination here but never a source.
 */
@RestController
@RequestMapping("/api/messages")
public class MessageForwardRestController {

    private final MessageForwardService forwards;
    private final ChannelService channelService;
    private final ConversationService conversationService;
    private final MarkdownRenderer markdown;
    private final CurrentUser currentUser;
    private final SimpMessagingTemplate broker;
    private final RateLimiter rateLimiter;
    private final ConversationAlertPublisher alerts;
    private final LinkPreviews linkPreviews;

    public MessageForwardRestController(MessageForwardService forwards,
                                        ChannelService channelService,
                                        ConversationService conversationService,
                                        MarkdownRenderer markdown,
                                        CurrentUser currentUser,
                                        SimpMessagingTemplate broker,
                                        RateLimiter rateLimiter,
                                        ConversationAlertPublisher alerts,
                                        LinkPreviews linkPreviews) {
        this.linkPreviews = linkPreviews;
        this.forwards = forwards;
        this.channelService = channelService;
        this.conversationService = conversationService;
        this.markdown = markdown;
        this.currentUser = currentUser;
        this.broker = broker;
        this.rateLimiter = rateLimiter;
        this.alerts = alerts;
    }

    @PostMapping("/{id}/forward")
    public Map<String, Object> forward(@PathVariable Long id,
                                       @RequestBody @Valid ForwardMessageRequest body,
                                       Principal principal) {
        var me = currentUser.resolve(principal);
        // A forward posts a message, so it spends the same budget posting one does. Without this,
        // the endpoint is a way to send 30 messages a minute to each of N rooms.
        if (!rateLimiter.tryAcquire(me.getUsername(), "http-send", 30, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("forward rate exceeded");
        }
        var toChannel = body.channelId() != null;
        var toConversation = body.conversationId() != null;
        if (toChannel == toConversation) {
            throw new PublicBadRequestException(
                    "Forward to exactly one destination: a channel or a conversation.");
        }

        if (toChannel) {
            var target = channelService.requireById(body.channelId());
            var posted = forwards.forwardToChannel(id, target, body.comment(),
                    body.acknowledgeDisclosure(), me);
            var saved = posted.message();
            var dto = MessageDto.from(saved, markdown.render(saved.getBodyMarkdown()),
                    List.of(), List.of(), 0L, posted.mentionedUsernames(), null);
            // whenDurable, not an immediate send: postWithMentions is durable on return today, but
            // the handle is the contract for "this row is really on disk" and a forward announced
            // before its insert commits is a message everyone saw and nobody has.
            posted.whenDurable(() -> {
                broker.convertAndSend("/topic/channels/" + target.getId(), MessageEvent.created(dto));
                linkPreviews.unfurl(dto);
            });
            return Map.of("kind", "channel", "channelId", target.getId(), "message", dto);
        }

        var target = conversationService.requireById(body.conversationId());
        var saved = forwards.forwardToConversation(id, target, body.comment(),
                body.acknowledgeDisclosure(), me);
        var dto = ConversationMessageDto.from(saved, markdown.renderInConversation(saved.getBodyMarkdown()));
        broker.convertAndSend("/topic/conversations/" + target.getId(), dto);
        linkPreviews.unfurl(dto);
        // The same alert the DM send path fires, so a forwarded message into a quiet DM is not
        // silent for the person on the other end.
        alerts.alert(target, saved);
        return Map.of("kind", "conversation", "conversationId", target.getId(), "message", dto);
    }
}
