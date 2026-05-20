package com.crm.service;

import com.crm.dto.MessageTemplateForm;
import com.crm.entity.MessageTemplate;
import com.crm.repository.MessageTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class MessageTemplateService {

    public static final int MAX_TEMPLATES = 50;

    private final MessageTemplateRepository repository;

    public MessageTemplateService(MessageTemplateRepository repository) {
        this.repository = repository;
    }

    public List<MessageTemplate> listAll() {
        return repository.findAllByOrderByDisplayOrderAscIdAsc();
    }

    public Optional<MessageTemplate> findById(Long id) {
        return repository.findById(id);
    }

    public long count() {
        return repository.count();
    }

    @Transactional
    public MessageTemplate create(MessageTemplateForm form) {
        if (repository.count() >= MAX_TEMPLATES) {
            throw new TooManyTemplatesException();
        }
        MessageTemplate t = new MessageTemplate();
        form.applyTo(t);
        return repository.save(t);
    }

    @Transactional
    public MessageTemplate update(Long id, MessageTemplateForm form) {
        MessageTemplate t = repository.findById(id)
                .orElseThrow(() -> new NotFoundException(id));
        form.applyTo(t);
        return repository.save(t);
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    /**
     * Apply a new display order from the drag-and-drop list page. The submitted {@code ids}
     * list is the desired top-to-bottom sequence; we just renumber DISPLAY_ORDER as 0..N-1
     * in that order. Any DB row whose id isn't in the submitted list is left alone (it'll
     * sort to the end on the next listAll() because displayOrder is unchanged).
     */
    @Transactional
    public void reorder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        int order = 0;
        for (Long id : ids) {
            if (id == null) continue;
            Optional<MessageTemplate> opt = repository.findById(id);
            if (!opt.isPresent()) continue;
            MessageTemplate t = opt.get();
            t.setDisplayOrder(order++);
            repository.save(t);
        }
    }

    public static class TooManyTemplatesException extends RuntimeException {
        public TooManyTemplatesException() {
            super("最大" + MAX_TEMPLATES + "件までしか登録できません");
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(Long id) { super("template not found: " + id); }
    }
}
