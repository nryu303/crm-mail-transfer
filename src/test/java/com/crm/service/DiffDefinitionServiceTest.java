package com.crm.service;

import com.crm.entity.DiffDefinition;
import com.crm.repository.DiffDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiffDefinitionServiceTest {

    private DiffDefinitionRepository repo;
    private DiffDefinitionService svc;

    @BeforeEach
    void setUp() {
        repo = mock(DiffDefinitionRepository.class);
        svc = new DiffDefinitionService(repo);
        when(repo.save(any(DiffDefinition.class))).thenAnswer(inv -> {
            DiffDefinition d = inv.getArgument(0);
            if (d.getId() == null) d.setId(1L);
            return d;
        });
    }

    @Test
    void create_rejectsWhenAtMaxDefinitions() {
        when(repo.count()).thenReturn(10L);

        assertThatThrownBy(() -> svc.create("new diff", 3))
                .isInstanceOf(DiffDefinitionService.TooManyDefinitionsException.class);
    }

    @Test
    void create_succeedsBelowMax() {
        when(repo.count()).thenReturn(9L);

        DiffDefinition d = svc.create("diffA", 5);

        assertThat(d.getName()).isEqualTo("diffA");
        assertThat(d.getMemoSlot()).isEqualTo(5);
    }

    @Test
    void create_clampsInvalidMemoSlotToValidRange() {
        when(repo.count()).thenReturn(0L);

        DiffDefinition low = svc.create("a", 0);
        DiffDefinition high = svc.create("b", 99);

        assertThat(low.getMemoSlot()).isEqualTo(1);
        assertThat(high.getMemoSlot()).isEqualTo(1);
    }

    @Test
    void update_returnsEmptyWhenNotFound() {
        when(repo.findById(42L)).thenReturn(Optional.empty());

        Optional<DiffDefinition> result = svc.update(42L, "x", 2);

        assertThat(result).isEmpty();
    }

    @Test
    void update_updatesNameAndSlot() {
        DiffDefinition existing = new DiffDefinition();
        existing.setId(3L);
        existing.setName("old");
        existing.setMemoSlot(1);
        when(repo.findById(3L)).thenReturn(Optional.of(existing));

        Optional<DiffDefinition> result = svc.update(3L, "new-name", 7);

        assertThat(result).isPresent();
        assertThat(existing.getName()).isEqualTo("new-name");
        assertThat(existing.getMemoSlot()).isEqualTo(7);
    }
}
