package com.crm.service;

import com.crm.entity.CrmUser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tag substitution rules — both built-in (%name%, %email%, %date_jp%) and per-user
 * custom (tag1..tag5). Behaviour is per the docstring on
 * {@link PlaceholderService#buildBindings}.
 */
class PlaceholderServiceTest {

    private final PlaceholderService svc = new PlaceholderService();

    private static CrmUser user(String displayName, String email) {
        CrmUser u = new CrmUser();
        u.setDisplayName(displayName);
        u.setEmail(email);
        return u;
    }

    @Test
    void substitutesBuiltInNameAndEmail() {
        CrmUser u = user("田中太郎", "tanaka@example.com");
        String out = svc.substitute("こんにちは %name% (%email%) さん", u);
        assertThat(out).isEqualTo("こんにちは 田中太郎 (tanaka@example.com) さん");
    }

    @Test
    void dateJpUsesJapaneseFormat() {
        CrmUser u = user("X", "x@x");
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN));
        assertThat(svc.substitute("本日は %date_jp% です", u))
                .isEqualTo("本日は " + today + " です");
    }

    @Test
    void nullTemplate_returnsNull() {
        assertThat(svc.substitute(null, user("a", "b@c"))).isNull();
    }

    @Test
    void nullUser_substitutesEmptyStringForBuiltIns() {
        // %date_jp% still works (no user dependency); name/email become "".
        String out = svc.substitute("[%name%]<%email%>", null);
        assertThat(out).isEqualTo("[]<>");
    }

    @Test
    void unknownTag_isLeftUnchanged() {
        CrmUser u = user("a", "b@c");
        assertThat(svc.substitute("%unknown_tag%", u)).isEqualTo("%unknown_tag%");
    }

    @Test
    void customTagFromUser_isSubstituted() {
        CrmUser u = user("a", "b@c");
        u.setTag1Key("amount");
        u.setTag1Value("1,200円");
        assertThat(svc.substitute("お支払い金額は %amount% です", u))
                .isEqualTo("お支払い金額は 1,200円 です");
    }

    @Test
    void customTagKey_isAutoWrappedWithPercentSigns() {
        CrmUser u = user("a", "b@c");
        u.setTag1Key("plan");  // no surrounding %, the service should add them
        u.setTag1Value("Premium");
        assertThat(svc.substitute("プランは %plan%", u))
                .isEqualTo("プランは Premium");
    }

    @Test
    void customTagKey_explicitPercentSigns_areKept() {
        CrmUser u = user("a", "b@c");
        u.setTag1Key("%explicit%");
        u.setTag1Value("v");
        assertThat(svc.substitute("[%explicit%]", u)).isEqualTo("[v]");
    }

    @Test
    void emptyOrNullCustomValue_doesNotOverrideBuiltIn() {
        // The user typed a key like "date_jp" with an empty value — the auto today-date should win.
        CrmUser u = user("a", "b@c");
        u.setTag1Key("date_jp");
        u.setTag1Value("");
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN));
        assertThat(svc.substitute("%date_jp%", u)).isEqualTo(today);
    }

    @Test
    void customTagOverridesBuiltIn_whenValueProvided() {
        // User explicitly customised %email% to a label — overrides the built-in.
        CrmUser u = user("a", "real@email");
        u.setTag1Key("email");
        u.setTag1Value("MASKED");
        assertThat(svc.substitute("%email%", u)).isEqualTo("MASKED");
    }

    @Test
    void blankCustomKey_isIgnored() {
        CrmUser u = user("a", "b@c");
        u.setTag1Key("   ");
        u.setTag1Value("anything");
        // Template contains no recognisable tag — blank key should not produce "% %" replacement.
        assertThat(svc.substitute("plain text", u)).isEqualTo("plain text");
    }

    @Test
    void buildBindings_returnsBuiltInsEvenWhenUserNull() {
        Map<String, String> m = svc.buildBindings(null);
        assertThat(m).containsKeys("%name%", "%email%", "%date_jp%");
    }
}
