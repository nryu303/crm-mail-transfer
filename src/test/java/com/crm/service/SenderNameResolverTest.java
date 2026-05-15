package com.crm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Display-name policy: none / fixed / list / random. Verifies each branch returns the
 * expected value space, and that bad/empty inputs degrade gracefully.
 */
class SenderNameResolverTest {

    private DomainSettingService settings;
    private SenderNameResolver svc;

    @BeforeEach
    void setUp() {
        settings = mock(DomainSettingService.class);
        svc = new SenderNameResolver(settings);
    }

    @Test
    void modeNone_returnsNull() {
        when(settings.getSenderNameMode()).thenReturn("none");
        assertThat(svc.resolve()).isNull();
    }

    @Test
    void modeFixed_returnsTrimmedFixedValue() {
        when(settings.getSenderNameMode()).thenReturn("fixed");
        when(settings.getSenderNameFixed()).thenReturn("  サポート窓口  ");
        assertThat(svc.resolve()).isEqualTo("サポート窓口");
    }

    @Test
    void modeFixed_blankFallsToNull() {
        when(settings.getSenderNameMode()).thenReturn("fixed");
        when(settings.getSenderNameFixed()).thenReturn("   ");
        assertThat(svc.resolve()).isNull();
    }

    @Test
    void modeList_picksOneFromList() {
        when(settings.getSenderNameMode()).thenReturn("list");
        when(settings.getSenderNameList()).thenReturn(Arrays.asList("田中", "佐藤", "鈴木"));
        // 50 picks must always be drawn from the allowed set.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) seen.add(svc.resolve());
        assertThat(seen).isSubsetOf(new HashSet<>(Arrays.asList("田中", "佐藤", "鈴木")));
    }

    @Test
    void modeList_empty_returnsNull() {
        when(settings.getSenderNameMode()).thenReturn("list");
        when(settings.getSenderNameList()).thenReturn(Collections.emptyList());
        assertThat(svc.resolve()).isNull();
    }

    @Test
    void modeRandom_obeysLengthAndAlphabet_lowerCase() {
        when(settings.getSenderNameMode()).thenReturn("random");
        when(settings.getSenderNameRandomLength()).thenReturn(8);
        when(settings.getSenderNameRandomCase()).thenReturn("lower");
        for (int i = 0; i < 50; i++) {
            String s = svc.resolve();
            assertThat(s).hasSize(8);
            assertThat(s).matches("[a-z0-9]+");
        }
    }

    @Test
    void modeRandom_obeysLengthAndAlphabet_upperCase() {
        when(settings.getSenderNameMode()).thenReturn("random");
        when(settings.getSenderNameRandomLength()).thenReturn(6);
        when(settings.getSenderNameRandomCase()).thenReturn("upper");
        for (int i = 0; i < 50; i++) {
            String s = svc.resolve();
            assertThat(s).hasSize(6);
            assertThat(s).matches("[A-Z0-9]+");
        }
    }

    @Test
    void modeRandom_mixedCase_isDefault() {
        when(settings.getSenderNameMode()).thenReturn("random");
        when(settings.getSenderNameRandomLength()).thenReturn(10);
        when(settings.getSenderNameRandomCase()).thenReturn("mixed");
        for (int i = 0; i < 50; i++) {
            String s = svc.resolve();
            assertThat(s).hasSize(10);
            assertThat(s).matches("[A-Za-z0-9]+");
        }
    }

    @Test
    void unknownMode_returnsNull() {
        when(settings.getSenderNameMode()).thenReturn("unrecognized-mode");
        assertThat(svc.resolve()).isNull();
    }
}
