package com.crm.service;

import com.crm.dto.RelayServerForm;
import com.crm.entity.RelayServer;
import com.crm.repository.RelayServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RelayServerService} — focuses on the single-active invariant
 * (operator confusion was the reason it was introduced: two rows being IS_ACTIVE=1
 * left dispatch behavior dependent on alphabetical ordering, which was non-obvious).
 */
class RelayServerServiceTest {

    private RelayServerRepository repo;
    private RelayServerService svc;

    @BeforeEach
    void setUp() {
        repo = mock(RelayServerRepository.class);
        when(repo.save(any(RelayServer.class))).thenAnswer(inv -> {
            RelayServer r = inv.getArgument(0);
            if (r.getId() == null) r.setId(99L);
            return r;
        });
        svc = new RelayServerService(repo);
    }

    private static RelayServer existing(Long id, String name, boolean active) {
        RelayServer r = new RelayServer();
        r.setId(id);
        r.setName(name);
        r.setIpAddress("10.0.0." + id);
        r.setPort(25);
        r.setIsActive(active);
        return r;
    }

    private static RelayServerForm formActive(String name, boolean active) {
        RelayServerForm f = new RelayServerForm();
        f.setName(name);
        f.setIpAddress("10.0.0.1");
        f.setPort(25);
        f.setIsActive(active);
        return f;
    }

    @Test
    void create_active_deactivatesAllOtherActiveRows() {
        when(repo.existsByName(anyString())).thenReturn(false);
        RelayServer existingActive1 = existing(1L, "A", true);
        RelayServer existingActive2 = existing(2L, "B", true);
        RelayServer existingInactive = existing(3L, "C", false);
        when(repo.findAll()).thenReturn(Arrays.asList(existingActive1, existingActive2, existingInactive));

        svc.create(formActive("NEW", true));

        // Both previously-active rows should be saved back as inactive; the inactive one is left alone.
        ArgumentCaptor<RelayServer> savedCap = ArgumentCaptor.forClass(RelayServer.class);
        verify(repo, atLeast(3)).save(savedCap.capture());
        assertThat(savedCap.getAllValues())
                .filteredOn(r -> r.getId() != null && (r.getId().equals(1L) || r.getId().equals(2L)))
                .allMatch(r -> Boolean.FALSE.equals(r.getIsActive()));
    }

    @Test
    void create_inactive_leavesOthersAlone() {
        when(repo.existsByName(anyString())).thenReturn(false);
        when(repo.findAll()).thenReturn(Arrays.asList(existing(1L, "A", true)));

        svc.create(formActive("NEW", false));
        // findAll should NOT be queried when the new row is inactive — there's nothing to demote.
        verify(repo, never()).findAll();
    }

    @Test
    void update_promotingToActive_deactivatesAllOtherActiveRows() {
        when(repo.findById(2L)).thenReturn(Optional.of(existing(2L, "B", false)));
        when(repo.existsByName(anyString())).thenReturn(false);
        RelayServer existingActive = existing(1L, "A", true);
        RelayServer subject = existing(2L, "B", false);
        when(repo.findAll()).thenReturn(Arrays.asList(existingActive, subject));

        svc.update(2L, formActive("B", true));

        ArgumentCaptor<RelayServer> savedCap = ArgumentCaptor.forClass(RelayServer.class);
        verify(repo, atLeast(2)).save(savedCap.capture());
        assertThat(savedCap.getAllValues())
                .filteredOn(r -> r.getId() != null && r.getId().equals(1L))
                .allMatch(r -> Boolean.FALSE.equals(r.getIsActive()));
    }

    @Test
    void update_demotingToInactive_doesNotTouchOthers() {
        when(repo.findById(1L)).thenReturn(Optional.of(existing(1L, "A", true)));
        when(repo.existsByName(anyString())).thenReturn(false);

        svc.update(1L, formActive("A", false));
        // findAll only runs when the saved row is active; demoting alone shouldn't trigger it.
        verify(repo, never()).findAll();
    }

    @Test
    void create_duplicateName_throwsDuplicateNameException() {
        when(repo.existsByName("A")).thenReturn(true);
        assertThatThrownBy(() -> svc.create(formActive("A", true)))
                .isInstanceOf(RelayServerService.DuplicateNameException.class);
        verify(repo, never()).save(any(RelayServer.class));
    }

    @Test
    void update_renameToExistingName_throwsDuplicateNameException() {
        when(repo.findById(1L)).thenReturn(Optional.of(existing(1L, "A", true)));
        when(repo.existsByName("B")).thenReturn(true);
        assertThatThrownBy(() -> svc.update(1L, formActive("B", true)))
                .isInstanceOf(RelayServerService.DuplicateNameException.class);
    }

    @Test
    void update_keepingSameName_doesNotCheckDuplicate() {
        when(repo.findById(1L)).thenReturn(Optional.of(existing(1L, "A", true)));
        when(repo.findAll()).thenReturn(Arrays.asList(existing(1L, "A", true)));
        // existsByName intentionally NOT stubbed — calling it would return false in Mockito anyway,
        // but the assertion below verifies the service skips the check when the name is unchanged.
        svc.update(1L, formActive("A", true));
        verify(repo, never()).existsByName("A");
    }

    @Test
    void update_nonExistentId_throwsNotFoundException() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.update(99L, formActive("X", false)))
                .isInstanceOf(RelayServerService.NotFoundException.class);
    }
}
