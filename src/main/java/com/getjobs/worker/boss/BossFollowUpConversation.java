package com.getjobs.worker.boss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.BossFollowUpStore.Job;
import com.getjobs.worker.boss.review.BossCandidateCollector;
import com.getjobs.worker.boss.review.BossReviewJob;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

import java.text.Normalizer;
import java.util.List;
import java.util.Objects;

/** 核对当前会话的消息方向、岗位身份和发送状态。 */
final class BossFollowUpConversation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final View UNAVAILABLE = new View(false, "", "", List.of(), List.of(), List.of());
    static final String PROBE = """
        () => {
          const visible = node => node && node.getClientRects().length > 0
            && getComputedStyle(node).visibility !== 'hidden';
          const text = node => (node?.innerText || node?.textContent || '').trim();
          const roots = [...document.querySelectorAll('.chat-message .im-list, .chat-record')].filter(visible);
          const root = roots.find(node => roots.every(other => node === other || other.contains(node)));
          if (!root) return null;
          const area = root.closest('.chat-message') || root;
          const chrome = selector => [...document.querySelectorAll(selector)].filter(node => visible(node)
            && !node.closest('.im-list, .chat-record, .user-list-content'));
          const headers = '.chat-info, .chat-header, .position-content, .chat-position';
          const jobLinks = [...new Set(chrome(headers.split(', ').map(h => h + ' a[href*="/job_detail/"]').join(','))
            .map(node => node.href))];
          const titles = [...new Set(chrome('.position-name, .chat-job-info .job-name')
            .map(text).filter(Boolean))];
          const participant = root.closest('[data-conversation-id], [data-boss-id], [data-user-id]');
          const query = new URL(location.href).searchParams;
          const participantId = participant?.getAttribute('data-conversation-id')
            || participant?.getAttribute('data-boss-id') || participant?.getAttribute('data-user-id')
            || query.get('encryptUserId') || query.get('bossId') || query.get('userId') || '';
          const name = text(chrome('.chat-info .name, .chat-header .name, .chat-info .user-name, .chat-header .user-name')[0]);
          const incomplete = area.matches('[aria-busy="true"]')
            || [...area.querySelectorAll('[aria-busy="true"], .load-more, .history-load-more, .loading-more, .message-loading')]
                .some(visible)
            || /滚动加载更多|点击加载更多|查看更多历史消息|过滤了.{0,40}发来的消息|历史消息未完整加载/.test(text(area));
          const today = new Date();
          const wallTime = (year, month, day, hour, minute, seconds) => {
            if (hour > 23 || minute > 59 || Number(seconds || 0) > 59) return 0;
            const at = new Date(year, month - 1, day, hour, minute, seconds == null ? 59 : Number(seconds), seconds == null ? 999 : 0);
            return at.getFullYear() === year && at.getMonth() === month - 1 && at.getDate() === day ? at.getTime() : 0;
          };
          const timeOf = value => {
            const raw = (value || '').trim().normalize('NFKC');
            if (/^\\d{10}$|^\\d{13}$/.test(raw)) return Number(raw) * (raw.length === 10 ? 1000 : 1);
            if (/^\\d{4}-\\d{2}-\\d{2}T.*(?:Z|[+-]\\d{2}:?\\d{2})$/.test(raw)) return Date.parse(raw) || 0;
            const exact = raw.match(/^(?:(\\d{4})[-年/])?(\\d{1,2})[-月/](\\d{1,2})日?[ T]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$/);
            if (exact) return wallTime(Number(exact[1] || today.getFullYear()), Number(exact[2]), Number(exact[3]), Number(exact[4]), Number(exact[5]), exact[6]);
            const relative = raw.match(/^(?:(今天|昨天|前天)\\s*)?(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$/);
            if (!relative) return 0;
            const offset = relative[1] === '昨天' ? 1 : relative[1] === '前天' ? 2 : 0;
            const day = new Date(today.getFullYear(), today.getMonth(), today.getDate() - offset);
            return wallTime(day.getFullYear(), day.getMonth() + 1, day.getDate(), Number(relative[2]), Number(relative[3]), relative[4]);
          };
          const messages = [...root.querySelectorAll('li.message-item')].map(node => {
            const own = node.classList.contains('item-myself');
            const incoming = node.classList.contains('item-friend');
            const system = !own && !incoming && node.matches('.item-system, .item-time, .message-time, .system-message');
            const statusNode = node.querySelector('.message-status, .send-status');
            const status = text(statusNode);
            const pending = node.matches('.sending, .uploading') || !!node.querySelector('.sending, .send-progress, .uploading')
              || /发送中|上传中/.test(status);
            const failed = node.matches('.message-fail, .send-fail') || !!node.querySelector('.message-fail, .send-fail, .send-error')
              || /发送失败|重新发送|点击重试/.test(status);
            const acknowledged = /^(已读|未读|已发送|发送成功|已送达|sent|delivered|read)$/i.test(status)
              || !!statusNode?.matches('.status-read, .status-delivery, .status-sent')
              || !!statusNode?.querySelector('.status-read, .status-delivery, .status-sent');
            const id = node.getAttribute('data-message-id') || node.getAttribute('data-msg-id') || node.getAttribute('data-mid') || '';
            const preceding = node.previousElementSibling;
            const timeNode = node.querySelector('time[datetime], .message-time, .msg-time')
              || (preceding?.matches('.item-time, .message-time, .msg-time') ? preceding : null);
            const timestamp = timeOf(node.getAttribute('data-timestamp')) || timeOf(node.getAttribute('data-time'))
              || timeOf(node.getAttribute('data-send-time')) || timeOf(timeNode?.getAttribute('datetime')) || timeOf(text(timeNode));
            return { own, incoming, system, acknowledged, pending, failed, id,
              content: text(node.querySelector('.message-content')), timestamp };
          });
          return JSON.stringify({ complete: !incomplete, participantId, name, jobLinks, titles, messages });
        }
        """;

    record Message(boolean own, boolean incoming, boolean system, boolean acknowledged, boolean pending, boolean failed,
                   String id, String content, long timestamp) {
        boolean settled() { return acknowledged && !pending && !failed; }
        boolean identifiable() { return !id.isBlank() || !content.isBlank(); }
        boolean sameMessage(Message other) {
            if (!id.isBlank() || !other.id.isBlank()) return !id.isBlank() && id.equals(other.id);
            return !content.isBlank() && content.equals(other.content) && timestamp == other.timestamp;
        }
    }

    record Anchor(Message first, Message latest, int sentCount) {
        boolean sameAs(Anchor other) {
            return other != null && sentCount == other.sentCount && first.sameMessage(other.first) && latest.sameMessage(other.latest);
        }
        String receiptKey() {
            return latest.id().isBlank() ? "text:" + sentCount + ":" + normalize(latest.content()) : "id:" + latest.id();
        }
    }

    record View(boolean complete, String participantId, String name, List<String> jobLinks, List<String> titles,
                List<Message> messages) {
        List<Message> sentMessages() { return messages.stream().filter(message -> message.own() && !message.failed()).toList(); }
        boolean matched(Job job) {
            if (!jobLinks.isEmpty()) {
                return jobLinks.stream().map(BossReviewJob::safeDetailUrl)
                        .map(BossCandidateCollector::idFromUrl).distinct().toList().equals(List.of(job.id()));
            }
            return !normalize(job.title()).isBlank()
                    && titles.stream().map(BossFollowUpConversation::normalize).distinct().toList().equals(List.of(normalize(job.title())));
        }
        boolean answered() {
            return messages.stream().anyMatch(Message::incoming);
        }
        Anchor unanswered(BossFollowUpPolicy.Window window) {
            if (!complete || messages.stream().anyMatch(message -> (!message.own() && !message.system()) || message.pending())) return null;
            var sent = sentMessages();
            if (sent.isEmpty()) return null;
            var first = sent.getFirst();
            var latest = sent.getLast();
            return first.identifiable() && latest.identifiable() && window.allows(first.timestamp())
                    ? new Anchor(first, latest, sent.size()) : null;
        }
        String conversationKey(Job job, Message first) {
            if (!participantId.isBlank()) return "participant:" + participantId;
            if (job.recruiterId() != null && !job.recruiterId().isBlank()) return "participant:" + job.recruiterId();
            if (!first.id().isBlank()) return "message:" + first.id();
            if (!normalize(name).isBlank() && !normalize(job.company()).isBlank())
                return "name:" + normalize(job.company()) + "|" + normalize(name);
            return "";
        }
        Anchor confirmation(Job job, String key, Anchor previous, String text, long clickedAt) {
            if (!complete || !matched(job) || !key.equals(conversationKey(job, previous.first()))) return null;
            var sent = sentMessages();
            if (sent.size() != previous.sentCount() + 1 || !sent.getFirst().sameMessage(previous.first())
                    || !sent.get(sent.size() - 2).sameMessage(previous.latest())) return null;
            var latest = sent.getLast();
            return latest.settled() && normalize(latest.content()).equals(normalize(text))
                    && (latest.id().isEmpty() || !latest.id().equals(previous.latest().id()))
                    && (latest.timestamp() == 0 || latest.timestamp() >= clickedAt - 2_000)
                    ? new Anchor(sent.getFirst(), latest, sent.size()) : null;
        }
    }

    private BossFollowUpConversation() {}

    static View read(Page page) {
        try {
            Object raw = page.evaluate(PROBE);
            if (raw instanceof String value) return JSON.readValue(value, View.class);
        } catch (PlaywrightException moving) {
            // 会话切换中，稍后重新核对。
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            // 无法识别页面结构时不确认。
        }
        return UNAVAILABLE;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(Objects.toString(text, ""), Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}]+", " ").strip();
    }
}
