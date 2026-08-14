package com.crm.repository;

import com.crm.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long>, JpaSpecificationExecutor<Message> {

    /** Chronological thread for one user (oldest first). Used by inbound bulk delete. */
    List<Message> findByUserIdOrderByCreatedAtAsc(Long userId);

    /** Reverse-chronological thread for the user-detail thread pane (newest at top). */
    List<Message> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Recent messages across all users (for the /manager/messages list). */
    Page<Message> findAllByOrderByCreatedAtDesc(Pageable pageable);

    int countByUserIdAndDirectionAndReadAtIsNull(Long userId, String direction);

    /** Count OUT messages for {@code userId} that were finalised (sent/sent-failed/sent-success)
     *  during the half-open window {@code [since, now)}, *excluding* the scheduled broadcast row
     *  whose dispatch we are about to evaluate. Used to drop a queued broadcast row when the
     *  same user already received some OTHER send after the broadcast was scheduled. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(m) FROM Message m WHERE m.userId = :userId AND m.direction = 'OUT' " +
            "AND (m.status = 'SENT' OR m.sentAt IS NOT NULL) " +
            "AND m.sentAt >= :since AND m.id <> :excludeId")
    long countOutboundFinalisedSince(@org.springframework.data.repository.query.Param("userId") Long userId,
                                       @org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since,
                                       @org.springframework.data.repository.query.Param("excludeId") Long excludeMessageId);

    long countByDirectionAndReadAtIsNull(String direction);

    long countByStatus(String status);

    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(m) FROM Message m WHERE m.direction = :dir AND m.status IN :statuses AND m.sentAt >= :from")
    long countSentSince(@org.springframework.data.repository.query.Param("dir") String direction,
                         @org.springframework.data.repository.query.Param("statuses") java.util.Collection<String> statuses,
                         @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            "UPDATE Message m SET m.readAt = :now WHERE m.userId = :userId AND m.direction = :dir AND m.readAt IS NULL")
    int markReadByUserAndDirection(@org.springframework.data.repository.query.Param("userId") Long userId,
                                    @org.springframework.data.repository.query.Param("dir") String direction,
                                    @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    /**
     * Bulk-flip all QUEUED messages of a broadcast to CANCELLED in one UPDATE. Replaces the
     * old per-row save loop in BroadcastService.cancel(), which on a 5K-row broadcast took
     * long enough that the dispatcher's race gate could not stop the leak in time.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            "UPDATE Message m SET m.status = 'CANCELLED', m.updatedAt = :now "
          + "WHERE m.broadcastId = :broadcastId AND m.status = 'QUEUED'")
    int cancelQueuedByBroadcastId(@org.springframework.data.repository.query.Param("broadcastId") Long broadcastId,
                                   @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    List<Message> findByStatusAndScheduledAtLessThanEqual(String status, java.time.LocalDateTime now);

    /**
     * QUEUED messages that are ready to dispatch now. A message is "due" if either:
     *   - SCHEDULED_AT has arrived (original user-requested schedule), or
     *   - NEXT_RETRY_AT has arrived (transient-failure retry).
     * Messages with no timestamp at all are also picked up (fresh queues).
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT m FROM Message m WHERE m.status = :status AND (" +
            "   (m.scheduledAt IS NULL AND m.nextRetryAt IS NULL)" +
            "   OR (m.scheduledAt IS NOT NULL AND m.scheduledAt <= :now)" +
            "   OR (m.nextRetryAt IS NOT NULL AND m.nextRetryAt <= :now))")
    List<Message> findDueForDispatch(@org.springframework.data.repository.query.Param("status") String status,
                                     @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    boolean existsByMessageIdHeader(String messageIdHeader);

    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(m) FROM Message m WHERE m.userId = :userId AND m.direction = :direction")
    long countByUserIdAndDirection(@org.springframework.data.repository.query.Param("userId") Long userId,
                                   @org.springframework.data.repository.query.Param("direction") String direction);

    /** Earliest OUTbound timestamp for a user — used by InboundMailService to detect
     *  inbound mail whose Date predates our first send (= reply to a previous owner of
     *  the same softbank carrier address, not a real reply to us). Returns null if the
     *  user has no OUT history yet. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT MIN(m.createdAt) FROM Message m WHERE m.userId = :userId AND m.direction = 'OUT'")
    java.time.LocalDateTime findEarliestOutboundDate(
            @org.springframework.data.repository.query.Param("userId") Long userId);

    /** How many MESSAGE rows in this broadcast still hold the given status. Used by
     *  the stuck-broadcast sweeper to detect parents whose children all finalised. */
    long countByBroadcastIdAndStatus(Long broadcastId, String status);

    /** Bulk-delete every MESSAGE row whose user_id is in the given list. Used by
     *  FolderRetentionService for the operator's per-folder "履歴削除" button. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            "DELETE FROM Message m WHERE m.userId IN :userIds")
    int deleteByUserIdIn(@org.springframework.data.repository.query.Param("userIds") java.util.List<Long> userIds);

    /** Bulk-delete MESSAGE rows older than {@code cutoff} for users in the list.
     *  Used by the daily retention sweeper. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            "DELETE FROM Message m WHERE m.userId IN :userIds AND m.createdAt < :cutoff")
    int deleteByUserIdInAndCreatedAtBefore(
            @org.springframework.data.repository.query.Param("userIds") java.util.List<Long> userIds,
            @org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff);

    @org.springframework.data.jpa.repository.Query(
            "SELECT MAX(m.createdAt) FROM Message m WHERE m.userId = :userId AND m.direction = :direction")
    java.time.LocalDateTime maxCreatedAtByUserIdAndDirection(@org.springframework.data.repository.query.Param("userId") Long userId,
                                                              @org.springframework.data.repository.query.Param("direction") String direction);

    /**
     * Integrated history for /manager/messages/broadcast: every OUT message dispatched by
     * a broadcast PLUS every IN reply pointing back to one of those OUT messages. Result is
     * paginated and ordered by createdAt DESC. Optional address filter matches either
     * fromAddress or toAddress (LIKE, lower-cased — pass a pre-built pattern or null).
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT m FROM Message m WHERE (" +
            "    m.broadcastId IS NOT NULL " +
            "    OR m.replyToMessageId IN (SELECT m2.id FROM Message m2 WHERE m2.broadcastId IS NOT NULL)" +
            ") AND (:addrLike IS NULL " +
            "       OR LOWER(m.toAddress) LIKE :addrLike " +
            "       OR LOWER(m.fromAddress) LIKE :addrLike)" +
            "   AND (:channel IS NULL OR m.channel = :channel)")
    Page<Message> findBroadcastRelated(@org.springframework.data.repository.query.Param("addrLike") String addrLike,
                                       @org.springframework.data.repository.query.Param("channel") String channel,
                                       Pageable pageable);

    /** Count messages matching direction (and non-draft status) within a time window. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(m) FROM Message m WHERE m.direction = :dir AND m.createdAt >= :from AND m.createdAt < :to")
    long countByDirectionBetween(@org.springframework.data.repository.query.Param("dir") String direction,
                                  @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
                                  @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

    /** Count outbound messages with a specific status within a time window. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(m) FROM Message m WHERE m.direction = :dir AND m.status = :status " +
            "AND m.createdAt >= :from AND m.createdAt < :to")
    long countByDirectionAndStatusBetween(@org.springframework.data.repository.query.Param("dir") String direction,
                                          @org.springframework.data.repository.query.Param("status") String status,
                                          @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
                                          @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

    /** Count messages matching direction + channel within a time window (SMS dashboard stats). */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(m) FROM Message m WHERE m.direction = :dir AND m.channel = :channel " +
            "AND m.createdAt >= :from AND m.createdAt < :to")
    long countByDirectionAndChannelBetween(@org.springframework.data.repository.query.Param("dir") String direction,
                                           @org.springframework.data.repository.query.Param("channel") String channel,
                                           @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
                                           @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

    /**
     * Last successful outbound timestamp per user. Returns rows {userId, MAX(sentAt)} for
     * messages where direction='OUT' AND status='SENT'.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT m.userId, MAX(m.sentAt) FROM Message m " +
            "WHERE m.direction = 'OUT' AND m.status = 'SENT' AND m.sentAt IS NOT NULL " +
            "GROUP BY m.userId")
    java.util.List<Object[]> lastSentAtByUser();

    /**
     * Last user-reply timestamp per user. Returns rows {userId, MAX(createdAt)} for
     * direction='IN' messages — i.e. when the user last replied to us. Used by the user
     * list "最終送信" column (operator-facing label refers to the user's send, not ours).
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT m.userId, MAX(m.createdAt) FROM Message m " +
            "WHERE m.direction = 'IN' " +
            "GROUP BY m.userId")
    java.util.List<Object[]> lastInboundAtByUser();

    /**
     * Inbox aggregation: one row per user who has any non-dismissed inbound messages.
     * Returns [userId, latestINId, unreadCount, webReplyCount, mailReplyCount, outCount,
     *          latestInAt, latestOutAt] ordered by latestINId DESC.
     *
     * IN rows with INBOX_DISMISSED_AT set are ignored everywhere in the IN aggregates so the
     * user disappears from the 受信 list after admin dismisses them. The OUT aggregates and
     * latestOutAt are NOT filtered — they reflect every OUT the user ever got, which is what
     * we need to decide 未返信 (unreplied) state on the rows that DO show up.
     */
    @org.springframework.data.jpa.repository.Query(nativeQuery = true, value =
            "SELECT m.USER_ID, " +
            "       MAX(CASE WHEN m.DIRECTION='IN' AND m.INBOX_DISMISSED_AT IS NULL THEN m.ID ELSE NULL END) AS latest_in_id, " +
            "       SUM(CASE WHEN m.DIRECTION='IN' AND m.INBOX_DISMISSED_AT IS NULL AND m.READ_AT IS NULL THEN 1 ELSE 0 END) AS unread, " +
            "       SUM(CASE WHEN m.DIRECTION='IN' AND m.INBOX_DISMISSED_AT IS NULL AND m.CHANNEL='WEB_REPLY' THEN 1 ELSE 0 END) AS web_reply, " +
            "       SUM(CASE WHEN m.DIRECTION='IN' AND m.INBOX_DISMISSED_AT IS NULL AND m.CHANNEL='EMAIL' THEN 1 ELSE 0 END) AS mail_reply, " +
            "       SUM(CASE WHEN m.DIRECTION='OUT' THEN 1 ELSE 0 END) AS sent, " +
            "       MAX(CASE WHEN m.DIRECTION='IN' AND m.INBOX_DISMISSED_AT IS NULL THEN m.CREATED_AT ELSE NULL END) AS latest_in_at, " +
            "       MAX(CASE WHEN m.DIRECTION='OUT' THEN m.CREATED_AT ELSE NULL END) AS latest_out_at " +
            "FROM MESSAGE m " +
            "GROUP BY m.USER_ID " +
            "HAVING MAX(CASE WHEN m.DIRECTION='IN' AND m.INBOX_DISMISSED_AT IS NULL THEN m.ID ELSE NULL END) IS NOT NULL " +
            "ORDER BY MAX(CASE WHEN m.DIRECTION='IN' AND m.INBOX_DISMISSED_AT IS NULL THEN m.ID ELSE NULL END) DESC")
    java.util.List<Object[]> inboxGroupByUser();

    /**
     * Dismiss every non-dismissed inbound message for one user. Called when the operator
     * clicks the per-row × button on the thread page's left-upper 受信 list — the user is
     * removed from the inbox aggregate, while every Message row stays in place so the
     * 過去のやり取り pane and per-user thread view are unaffected.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            "UPDATE Message m SET m.inboxDismissedAt = :now " +
            "WHERE m.userId = :userId AND m.direction = 'IN' AND m.inboxDismissedAt IS NULL")
    int dismissInboxByUserId(@org.springframework.data.repository.query.Param("userId") Long userId,
                              @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    /**
     * メッセージボックス: paginated OUT/SENT history for one user, newest sentAt first,
     * excluding messages whose reply-URL was built against a REDIRECT/CUSTOM_HTML
     * 外部リンクドメイン at compose time (EXCLUDED_FROM_BOX) and excluding any the admin
     * has soft-deleted (BOX_DISMISSED_AT). Shared by the public /reply/{token} footer
     * section and the admin /manager/users/{id}/message-box preview.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT m FROM Message m WHERE m.userId = :userId " +
            "AND m.direction = 'OUT' AND m.status = 'SENT' " +
            "AND m.channel IN ('EMAIL','SMS','BROADCAST') " +
            "AND m.excludedFromBox = false " +
            "AND m.boxDismissedAt IS NULL " +
            "ORDER BY m.sentAt DESC")
    Page<Message> findMessageBoxPage(@org.springframework.data.repository.query.Param("userId") Long userId,
                                      Pageable pageable);

    /**
     * Admin-only per-user メッセージボックス soft-delete (選択削除 button). Distinct from
     * dismissInboxByUserId — that dismisses IN rows globally for /manager/inbox; this
     * dismisses specific OUT rows for one user's message-box view only, and Message rows
     * are never deleted so thread history is unaffected. Scoped to {@code userId} so a
     * tampered form post can't dismiss another user's messages.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            "UPDATE Message m SET m.boxDismissedAt = :now " +
            "WHERE m.userId = :userId AND m.id IN :ids AND m.boxDismissedAt IS NULL")
    int dismissBoxByUserIdAndIds(@org.springframework.data.repository.query.Param("userId") Long userId,
                                  @org.springframework.data.repository.query.Param("ids") java.util.List<Long> ids,
                                  @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);
}
