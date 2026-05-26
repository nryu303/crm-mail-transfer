package com.crm.service;

import com.crm.entity.ReplyPageAttachment;
import com.crm.repository.ReplyPageAttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Manages image attachments uploaded by users on the public reply page.
 *
 * <p>Storage layout: files live on disk under {@link #uploadsRoot}/{userId}/{slot}/{uuid}.{ext}.
 * The DB row holds the original filename plus the stored relative path so the file can be
 * served back without exposing the host filesystem layout.
 *
 * <p>Hard limits intentionally conservative — operator can lift later if needed:
 *   * Max size: 5 MB per file
 *   * MIME whitelist: JPEG / PNG / GIF / WebP
 *   * Max attachments per user per slot: 20 (prevents abuse of the public endpoint)
 */
@Service
public class ReplyAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(ReplyAttachmentService.class);

    public static final long MAX_SIZE_BYTES = 5L * 1024 * 1024; // 5 MB
    public static final int MAX_PER_USER_SLOT = 20;
    public static final Set<String> ALLOWED_MIME = new java.util.HashSet<>(Arrays.asList(
            "image/jpeg", "image/png", "image/gif", "image/webp"));

    private final ReplyPageAttachmentRepository repo;
    private final Path uploadsRoot;

    public ReplyAttachmentService(ReplyPageAttachmentRepository repo,
                                  @Value("${app.uploads-root:/home/centos/crm-platform/uploads/reply-attachments}")
                                  String uploadsRoot) {
        this.repo = repo;
        this.uploadsRoot = Paths.get(uploadsRoot);
        try {
            Files.createDirectories(this.uploadsRoot);
        } catch (IOException e) {
            log.warn("Failed to create uploads root {}: {}", uploadsRoot, e.toString());
        }
    }

    public List<ReplyPageAttachment> listForUserSlot(Long userId, Integer slot) {
        int s = (slot == null || slot < 1 || slot > 6) ? 1 : slot;
        return repo.findByUserIdAndSlotNoOrderByCreatedAtDesc(userId, s);
    }

    /** Convenience for the public reply page — caller passes the user's active slot. */
    public List<ReplyPageAttachment> listForActiveSlot(Long userId, Integer activeSlot) {
        return listForUserSlot(userId, activeSlot);
    }

    /** Returns the attachment row IF it belongs to the supplied user. Caller validates
     *  the user (via reply-page token or admin session). null userId means no ownership
     *  filter (admin path). */
    public java.util.Optional<ReplyPageAttachment> findById(Long id, Long ownerUserId) {
        return repo.findById(id).filter(a -> ownerUserId == null || ownerUserId.equals(a.getUserId()));
    }

    /** Stream the stored bytes for a given attachment id. Returns null if missing. */
    public File fileFor(ReplyPageAttachment att) {
        if (att == null || att.getStoredPath() == null) return null;
        File f = uploadsRoot.resolve(att.getStoredPath()).toFile();
        return f.isFile() ? f : null;
    }

    /** Save an uploaded file. Throws AttachmentException on any policy violation
     *  (size / mime / per-slot cap). The MultipartFile is consumed exactly once. */
    @Transactional
    public ReplyPageAttachment upload(Long userId, int slot, MultipartFile file, String uploadedBy)
            throws AttachmentException, IOException {
        if (userId == null) throw new AttachmentException("user not resolved");
        if (file == null || file.isEmpty()) throw new AttachmentException("ファイルが空です");
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new AttachmentException("ファイルサイズは " + (MAX_SIZE_BYTES / 1024 / 1024) + " MB 以内にしてください");
        }
        String mime = file.getContentType();
        if (mime == null || !ALLOWED_MIME.contains(mime.toLowerCase(Locale.ROOT))) {
            throw new AttachmentException("画像ファイル (JPEG / PNG / GIF / WebP) のみアップロード可能です");
        }
        int s = (slot < 1 || slot > 6) ? 1 : slot;
        long existing = repo.countByUserIdAndSlotNo(userId, s);
        if (existing >= MAX_PER_USER_SLOT) {
            throw new AttachmentException(
                    "1つの返信HTMLスロットにつき最大 " + MAX_PER_USER_SLOT + " 件まで添付できます");
        }

        // /<userId>/<slot>/<uuid>.<ext>
        String ext = pickExtension(file.getOriginalFilename(), mime);
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String rel = userId + "/" + s + "/" + uuid + "." + ext;
        Path dest = uploadsRoot.resolve(rel);
        Files.createDirectories(dest.getParent());
        try (java.io.InputStream in = file.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }

        ReplyPageAttachment a = new ReplyPageAttachment();
        a.setUserId(userId);
        a.setSlotNo(s);
        a.setFileName(sanitiseFilename(file.getOriginalFilename(), ext));
        a.setStoredPath(rel);
        a.setContentType(mime.toLowerCase(Locale.ROOT));
        a.setSizeBytes(file.getSize());
        a.setUploadedBy(uploadedBy);
        a.setCreatedAt(LocalDateTime.now());
        return repo.save(a);
    }

    /** Admin-side hard delete: removes the DB row + the on-disk file. */
    @Transactional
    public boolean deleteById(Long id) {
        return repo.findById(id).map(att -> {
            File f = uploadsRoot.resolve(att.getStoredPath()).toFile();
            if (f.isFile()) {
                if (!f.delete()) log.warn("Could not delete attachment file {}", f.getAbsolutePath());
            }
            repo.delete(att);
            return true;
        }).orElse(false);
    }

    private static String pickExtension(String originalName, String mime) {
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot > 0 && dot < originalName.length() - 1) {
                String ext = originalName.substring(dot + 1).toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]", "");
                if (ext.length() >= 2 && ext.length() <= 5) return ext;
            }
        }
        switch (mime == null ? "" : mime.toLowerCase(Locale.ROOT)) {
            case "image/png":  return "png";
            case "image/gif":  return "gif";
            case "image/webp": return "webp";
            default:           return "jpg";
        }
    }

    private static String sanitiseFilename(String original, String ext) {
        if (original == null || original.isEmpty()) return "image." + ext;
        // Strip directory parts, leave the user-visible basename only.
        String name = original.replaceAll(".*[\\\\/]", "");
        if (name.length() > 240) name = name.substring(0, 240);
        return name;
    }

    public static class AttachmentException extends RuntimeException {
        public AttachmentException(String msg) { super(msg); }
    }
}
