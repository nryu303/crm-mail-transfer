package com.crm.controller;

import com.crm.entity.CrmUser;
import com.crm.entity.ExternalLinkDomain;
import com.crm.entity.ReplyPage;
import com.crm.entity.ReplyPageSetting;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.MessageRepository;
import com.crm.service.ExternalLinkDomainService;
import com.crm.service.MessageBoxService;
import com.crm.service.PlaceholderService;
import com.crm.service.ReplyAttachmentService;
import com.crm.service.ReplyPageService;
import com.crm.service.ReplyPageSettingService;
import com.crm.service.ReplyRateLimitService;
import com.crm.service.UserActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GET /reply/{token} 外部リンクドメイン生成 branching: once the access is logged, a domain
 * registered with a real A record pointed at the CRM (i.e. resolvable via Host header) can be
 * configured to redirect to an external URL, serve custom landing HTML, or fall through to the
 * normal two-way reply form (default / unregistered host / legacy base-URL domains).
 */
class ReplyPageControllerTest {

    private ReplyPageService replyPageService;
    private CrmUserRepository userRepository;
    private MessageRepository messageRepository;
    private ReplyPageSettingService settingService;
    private UserActivityService userActivityService;
    private ReplyRateLimitService rateLimitService;
    private PlaceholderService placeholderService;
    private ReplyAttachmentService attachmentService;
    private ExternalLinkDomainService externalLinkDomainService;
    private MessageBoxService messageBoxService;
    private ReplyPageController controller;

    @BeforeEach
    void setUp() {
        replyPageService = mock(ReplyPageService.class);
        userRepository = mock(CrmUserRepository.class);
        messageRepository = mock(MessageRepository.class);
        settingService = mock(ReplyPageSettingService.class);
        userActivityService = mock(UserActivityService.class);
        rateLimitService = mock(ReplyRateLimitService.class);
        placeholderService = mock(PlaceholderService.class);
        attachmentService = mock(ReplyAttachmentService.class);
        externalLinkDomainService = mock(ExternalLinkDomainService.class);
        messageBoxService = mock(MessageBoxService.class);

        controller = new ReplyPageController(replyPageService, userRepository, messageRepository,
                settingService, userActivityService, rateLimitService, placeholderService,
                attachmentService, externalLinkDomainService, messageBoxService);

        when(settingService.getOrCreate()).thenReturn(new ReplyPageSetting());
        when(attachmentService.listForActiveSlot(any(), any())).thenReturn(Collections.emptyList());
        when(placeholderService.substitute(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageBoxService.listFor(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(org.springframework.data.domain.Page.empty());
    }

    private static ReplyPage usablePage(Long userId) {
        ReplyPage rp = new ReplyPage();
        rp.setToken("tok123");
        rp.setUserId(userId);
        rp.setIsActive(true);
        return rp;
    }

    private static CrmUser activeUser(Long id) {
        CrmUser u = new CrmUser();
        u.setId(id);
        u.setStatus(CrmUser.STATUS_ACTIVE);
        return u;
    }

    private static ExternalLinkDomain domainWithMode(String mode, String redirectUrl, String html) {
        ExternalLinkDomain d = new ExternalLinkDomain();
        d.setLandingMode(mode);
        d.setRedirectUrl(redirectUrl);
        d.setLandingHtml(html);
        return d;
    }

    @Test
    void redirectMode_logsAccessThenReturnsRedirectView() {
        when(replyPageService.findByToken("tok123")).thenReturn(Optional.of(usablePage(1L)));
        when(replyPageService.isUsable(any())).thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L)));
        when(externalLinkDomainService.findByHost("ii5gh9ge.jp"))
                .thenReturn(Optional.of(domainWithMode(ExternalLinkDomain.MODE_REDIRECT,
                        "https://www.yahoo.co.jp", null)));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServerName("ii5gh9ge.jp");
        Model model = new ExtendedModelMap();

        String view = controller.show("tok123", 0, req, model);

        assertThat(view).isEqualTo("redirect:https://www.yahoo.co.jp");
        verify(userActivityService).touchLastLogin(any(CrmUser.class), anyString(), any(), any(), anyString());
    }

    @Test
    void customHtmlMode_logsAccessThenReturnsLandingView() {
        when(replyPageService.findByToken("tok123")).thenReturn(Optional.of(usablePage(1L)));
        when(replyPageService.isUsable(any())).thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L)));
        when(externalLinkDomainService.findByHost("ii5gh9ge.jp"))
                .thenReturn(Optional.of(domainWithMode(ExternalLinkDomain.MODE_CUSTOM_HTML,
                        null, "<html>fake landing</html>")));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServerName("ii5gh9ge.jp");
        Model model = new ExtendedModelMap();

        String view = controller.show("tok123", 0, req, model);

        assertThat(view).isEqualTo("reply/landing");
        assertThat(model.getAttribute("landingHtml")).isEqualTo("<html>fake landing</html>");
        verify(userActivityService).touchLastLogin(any(CrmUser.class), anyString(), any(), any(), anyString());
    }

    @Test
    void replyFormMode_fallsThroughToNormalReplyPage() {
        when(replyPageService.findByToken("tok123")).thenReturn(Optional.of(usablePage(1L)));
        when(replyPageService.isUsable(any())).thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L)));
        when(externalLinkDomainService.findByHost("ii5gh9ge.jp"))
                .thenReturn(Optional.of(domainWithMode(ExternalLinkDomain.MODE_REPLY_FORM, null, null)));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServerName("ii5gh9ge.jp");
        Model model = new ExtendedModelMap();

        String view = controller.show("tok123", 0, req, model);

        assertThat(view).isEqualTo("reply/page");
    }

    @Test
    void unregisteredHost_fallsThroughToNormalReplyPage() {
        when(replyPageService.findByToken("tok123")).thenReturn(Optional.of(usablePage(1L)));
        when(replyPageService.isUsable(any())).thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L)));
        when(externalLinkDomainService.findByHost(anyString())).thenReturn(Optional.empty());

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServerName("nbbv7g.jp"); // legacy base-URL domain, no ExternalLinkDomain row
        Model model = new ExtendedModelMap();

        String view = controller.show("tok123", 0, req, model);

        assertThat(view).isEqualTo("reply/page");
    }

    @Test
    void redirectMode_withBlankRedirectUrl_fallsThroughToReplyForm() {
        when(replyPageService.findByToken("tok123")).thenReturn(Optional.of(usablePage(1L)));
        when(replyPageService.isUsable(any())).thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L)));
        when(externalLinkDomainService.findByHost("ii5gh9ge.jp"))
                .thenReturn(Optional.of(domainWithMode(ExternalLinkDomain.MODE_REDIRECT, "  ", null)));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServerName("ii5gh9ge.jp");
        Model model = new ExtendedModelMap();

        String view = controller.show("tok123", 0, req, model);

        assertThat(view).isEqualTo("reply/page");
    }
}
