package com.crm.service;

import com.crm.entity.HtmlImage;
import com.crm.repository.HtmlImageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtmlImageServiceTest {

    private HtmlImageRepository repo;
    private HtmlImageService svc;
    private Path tempRoot;

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(HtmlImageRepository.class);
        tempRoot = Files.createTempDirectory("html-image-test");
        svc = new HtmlImageService(repo, tempRoot.toString());
        when(repo.save(any(HtmlImage.class))).thenAnswer(inv -> {
            HtmlImage img = inv.getArgument(0);
            if (img.getId() == null) img.setId(1L);
            return img;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.walk(tempRoot).sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile).forEach(File::delete);
    }

    @Test
    void upload_rejectsOversizedFile() {
        byte[] big = new byte[(int) HtmlImageService.MAX_SIZE_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);

        assertThatThrownBy(() -> svc.upload(file, "label", "admin"))
                .isInstanceOf(HtmlImageService.HtmlImageException.class)
                .hasMessageContaining("MB");
    }

    @Test
    void upload_rejectsDisallowedMime() {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> svc.upload(file, "label", "admin"))
                .isInstanceOf(HtmlImageService.HtmlImageException.class)
                .hasMessageContaining("JPEG");
    }

    @Test
    void upload_savesFileAndReturnsEntity() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3, 4});

        HtmlImage saved = svc.upload(file, "  ラベル  ", "admin_taro");

        ArgumentCaptor<HtmlImage> captor = ArgumentCaptor.forClass(HtmlImage.class);
        verify(repo).save(captor.capture());
        HtmlImage img = captor.getValue();
        assertThat(img.getLabel()).isEqualTo("ラベル"); // trimmed
        assertThat(img.getFileName()).isEqualTo("photo.png");
        assertThat(img.getContentType()).isEqualTo("image/png");
        assertThat(img.getSizeBytes()).isEqualTo(4L);
        assertThat(img.getUploadedBy()).isEqualTo("admin_taro");
        assertThat(img.getStoredPath()).endsWith(".png");
        assertThat(tempRoot.resolve(img.getStoredPath())).exists();
        assertThat(saved).isSameAs(img);
    }

    @Test
    void upload_blankLabelBecomesNull() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1});

        svc.upload(file, "   ", null);

        ArgumentCaptor<HtmlImage> captor = ArgumentCaptor.forClass(HtmlImage.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getLabel()).isNull();
    }

    @Test
    void deleteById_removesDbRowAndDiskFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{9, 9});
        HtmlImage saved = svc.upload(file, null, null);
        saved.setId(42L);
        File onDisk = tempRoot.resolve(saved.getStoredPath()).toFile();
        assertThat(onDisk).exists();

        when(repo.findById(42L)).thenReturn(Optional.of(saved));

        boolean ok = svc.deleteById(42L);

        assertThat(ok).isTrue();
        assertThat(onDisk).doesNotExist();
        verify(repo).delete(saved);
    }

    @Test
    void deleteById_returnsFalseWhenNotFound() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        boolean ok = svc.deleteById(99L);

        assertThat(ok).isFalse();
    }

    @Test
    void updateLabel_updatesOnlyLabelField() {
        HtmlImage existing = new HtmlImage();
        existing.setId(5L);
        existing.setLabel("old");
        existing.setFileName("f.png");
        when(repo.findById(5L)).thenReturn(Optional.of(existing));

        boolean ok = svc.updateLabel(5L, "new label");

        assertThat(ok).isTrue();
        assertThat(existing.getLabel()).isEqualTo("new label");
        verify(repo).save(existing);
    }

    @Test
    void updateLabel_returnsFalseWhenNotFound() {
        when(repo.findById(7L)).thenReturn(Optional.empty());

        boolean ok = svc.updateLabel(7L, "x");

        assertThat(ok).isFalse();
    }
}
