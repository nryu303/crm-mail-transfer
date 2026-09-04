package com.crm.service;

import com.crm.entity.DiffDefinition;
import com.crm.repository.DiffDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** CRUD for reusable diff templates — capped at {@link #MAX_DEFINITIONS}. */
@Service
public class DiffDefinitionService {

    public static final int MAX_DEFINITIONS = 10;

    private final DiffDefinitionRepository repository;

    public DiffDefinitionService(DiffDefinitionRepository repository) {
        this.repository = repository;
    }

    public List<DiffDefinition> listAll() {
        return repository.findAllByOrderByDisplayOrderAscIdAsc();
    }

    public Optional<DiffDefinition> findById(Long id) {
        return repository.findById(id);
    }

    public long count() {
        return repository.count();
    }

    @Transactional
    public DiffDefinition create(String name, int memoSlot) {
        if (repository.count() >= MAX_DEFINITIONS) {
            throw new TooManyDefinitionsException();
        }
        DiffDefinition d = new DiffDefinition();
        d.setName(name);
        d.setMemoSlot(clampSlot(memoSlot));
        d.setDisplayOrder((int) repository.count());
        return repository.save(d);
    }

    @Transactional
    public Optional<DiffDefinition> update(Long id, String name, int memoSlot) {
        Optional<DiffDefinition> opt = repository.findById(id);
        if (!opt.isPresent()) return Optional.empty();
        DiffDefinition d = opt.get();
        d.setName(name);
        d.setMemoSlot(clampSlot(memoSlot));
        return Optional.of(repository.save(d));
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    private static int clampSlot(int slot) {
        return (slot < 1 || slot > 10) ? 1 : slot;
    }

    public static class TooManyDefinitionsException extends RuntimeException {
        public TooManyDefinitionsException() {
            super("差分定義は最大" + MAX_DEFINITIONS + "件までしか登録できません");
        }
    }
}
