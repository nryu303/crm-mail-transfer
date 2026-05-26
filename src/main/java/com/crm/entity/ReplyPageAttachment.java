package com.crm.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * One image attachment uploaded by a user via the public reply page.
 * Bound to a specific (userId, slotNo) pair — the slot the user's reply page
 * was on at upload time. When the admin switches that user's active slot, the
 * public reply page only shows attachments for the newly-active slot.
 */
@Entity
@Table(name = "REPLY_PAGE_ATTACHMENT")
public class ReplyPageAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "USER_ID", nullable = false)
    private Long userId;

    /** Reply-HTML slot (1..6) — operator switches between them via CrmUser.activeMemoSlot. */
    @Column(name = "SLOT_NO", nullable = false)
    private Integer slotNo;

    /** Operator-visible original filename. Sanitised by AttachmentService.upload(). */
    @Column(name = "FILE_NAME", nullable = false, length = 255)
    private String fileName;

    /** Path on disk relative to the app's uploads dir. Stored separately from FILE_NAME
     *  so we can rename / move on disk without breaking the operator-facing label. */
    @Column(name = "STORED_PATH", nullable = false, length = 500)
    private String storedPath;

    @Column(name = "CONTENT_TYPE", nullable = false, length = 120)
    private String contentType;

    @Column(name = "SIZE_BYTES", nullable = false)
    private Long sizeBytes;

    /** Free-form: usually the visitor IP from the reply-page POST, for audit. */
    @Column(name = "UPLOADED_BY", length = 40)
    private String uploadedBy;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (slotNo == null || slotNo < 1 || slotNo > 6) slotNo = 1;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Integer getSlotNo() { return slotNo; }
    public void setSlotNo(Integer slotNo) { this.slotNo = slotNo; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStoredPath() { return storedPath; }
    public void setStoredPath(String storedPath) { this.storedPath = storedPath; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
