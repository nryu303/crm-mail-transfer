package com.crm.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import java.time.LocalDateTime;

/** An operator-uploaded image, shared globally (not scoped to a user), served publicly at
 *  /img/{id} so it can be referenced from reply-page header/footer HTML or any other CRM
 *  HTML field and render both in the admin preview and on the public user-facing page. */
@Entity
@Table(name = "HTML_IMAGE")
public class HtmlImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "LABEL", length = 255)
    private String label;

    @Column(name = "FILE_NAME", nullable = false, length = 255)
    private String fileName;

    @Column(name = "STORED_PATH", nullable = false, length = 500)
    private String storedPath;

    @Column(name = "CONTENT_TYPE", nullable = false, length = 120)
    private String contentType;

    @Column(name = "SIZE_BYTES", nullable = false)
    private Long sizeBytes;

    @Column(name = "UPLOADED_BY", length = 40)
    private String uploadedBy;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
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
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
