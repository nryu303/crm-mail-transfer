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

    public static final int MAX_TEMPLATES = 25;

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

    public static class TooManyTemplatesException extends RuntimeException {
        public TooManyTemplatesException() {
            super("最大" + MAX_TEMPLATES + "件までしか登録できません");
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(Long id) { super("template not found: " + id); }
    }
}
