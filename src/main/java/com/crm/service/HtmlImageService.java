package com.crm.service;

import com.crm.entity.HtmlImage;
import com.crm.repository.HtmlImageRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Manages operator-uploaded images referenced from CRM HTML fields (reply-page header/footer
 * HTML, memo-slot HTML, etc.) and served publicly at /img/{id}.
 *
 * <p>Storage layout: files live flat under {@link #uploadsRoot}/{uuid}.{ext} — no per-user
 * directory since images here are shared/global, unlike {@link ReplyAttachmentService}'s
 * per-user reply attachments.
 */
@Service
public class HtmlImageService {

    private static final Logger log = LoggerFactory.getLogger(HtmlImageService.class);

    public static final long MAX_SIZE_BYTES = 5L * 1024 * 1024; // 5 MB
    public static final Set<String> ALLOWED_MIME = new java.util.HashSet<>(Arrays.asList(
            "image/jpeg", "image/png", "image/gif", "image/webp"));

    private final HtmlImageRepository repo;
    private final Path uploadsRoot;

    public HtmlImageService(HtmlImageRepository repo,
                             @Value("${app.html-images-uploads-root:/home/centos/crm-platform/uploads/html-images}")
                             String uploadsRoot) {
        this.repo = repo;
        this.uploadsRoot = Paths.get(uploadsRoot);
        try {
            Files.createDirectories(this.uploadsRoot);
        } catch (IOException e) {
            log.warn("Failed to create uploads root {}: {}", uploadsRoot, e.toString());
        }
    }

    public List<HtmlImage> listAll() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    public Optional<HtmlImage> findById(Long id) {
        return repo.findById(id);
    }

    public File fileFor(HtmlImage img) {
        if (img == null || img.getStoredPath() == null) return null;
        File f = uploadsRoot.resolve(img.getStoredPath()).toFile();
        return f.isFile() ? f : null;
    }

    @Transactional
    public HtmlImage upload(MultipartFile file, String label, String uploadedBy)
            throws HtmlImageException, IOException {
        if (file == null || file.isEmpty()) throw new HtmlImageException("ファイルが空です");
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new HtmlImageException("ファイルサイズは " + (MAX_SIZE_BYTES / 1024 / 1024) + " MB 以内にしてください");
        }
        String mime = file.getContentType();
        if (mime == null || !ALLOWED_MIME.contains(mime.toLowerCase(Locale.ROOT))) {
            throw new HtmlImageException("画像ファイル (JPEG / PNG / GIF / WebP) のみアップロード可能です");
        }

        String ext = pickExtension(file.getOriginalFilename(), mime);
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String rel = uuid + "." + ext;
        Path dest = uploadsRoot.resolve(rel);
        try (java.io.InputStream in = file.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }

        HtmlImage img = new HtmlImage();
        img.setLabel(label == null || label.trim().isEmpty() ? null : label.trim());
        img.setFileName(sanitiseFilename(file.getOriginalFilename(), ext));
        img.setStoredPath(rel);
        img.setContentType(mime.toLowerCase(Locale.ROOT));
        img.setSizeBytes(file.getSize());
        img.setUploadedBy(uploadedBy);
        return repo.save(img);
    }

    @Transactional
    public boolean updateLabel(Long id, String label) {
        return repo.findById(id).map(img -> {
            img.setLabel(label == null || label.trim().isEmpty() ? null : label.trim());
            repo.save(img);
            return true;
        }).orElse(false);
    }

    @Transactional
    public boolean deleteById(Long id) {
        return repo.findById(id).map(img -> {
            File f = uploadsRoot.resolve(img.getStoredPath()).toFile();
            if (f.isFile()) {
                if (!f.delete()) log.warn("Could not delete image file {}", f.getAbsolutePath());
            }
            repo.delete(img);
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
        String name = original.replaceAll(".*[\\\\/]", "");
        if (name.length() > 240) name = name.substring(0, 240);
        return name;
    }

    public static class HtmlImageException extends RuntimeException {
        public HtmlImageException(String msg) { super(msg); }
    }
}
