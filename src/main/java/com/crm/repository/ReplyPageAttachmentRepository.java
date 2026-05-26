package com.crm.repository;

import com.crm.entity.ReplyPageAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReplyPageAttachmentRepository extends JpaRepository<ReplyPageAttachment, Long> {

    /** Newest-first list for a given user + slot. Used by both the public reply page
     *  (only shows the user's currently-active slot) and the admin user-detail page
     *  (shows every slot's list separately). */
    List<ReplyPageAttachment> findByUserIdAndSlotNoOrderByCreatedAtDesc(Long userId, Integer slotNo);

    /** Every attachment for a user across all slots — admin user-detail page uses this
     *  if it ever needs a grand total. */
    List<ReplyPageAttachment> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndSlotNo(Long userId, Integer slotNo);

    /** Attachments attached to a specific received MESSAGE row. Used by the thread
     *  view to render thumbnails next to that inbound. */
    List<ReplyPageAttachment> findByMessageIdOrderByCreatedAtAsc(Long messageId);

    /** Bulk fetch across many inbound messages for the per-row map in the thread page. */
    List<ReplyPageAttachment> findByMessageIdIn(java.util.Collection<Long> messageIds);
}
