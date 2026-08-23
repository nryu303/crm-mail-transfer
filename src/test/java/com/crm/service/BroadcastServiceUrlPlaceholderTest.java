package com.crm.service;

import com.crm.dto.BroadcastForm;
import com.crm.entity.Broadcast;
import com.crm.entity.CrmUser;
import com.crm.entity.Message;
import com.crm.repository.BroadcastRepository;
import com.crm.repository.CarrierAddressPoolRepository;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers %reply_url% / %external_url% substitution in the broadcast send paths
 * (createAndQueue / createAndQueueSms), added 2026-08-23 alongside the same split in
 * MessageService.compose()/composeSms() — both now route through the shared
 * MessageService.applyUrlPlaceholders() helper rather than duplicating the logic.
 */
class BroadcastServiceUrlPlaceholderTest {

    private BroadcastRepository broadcastRepo;
    private CrmUserRepository userRepo;
    private CarrierAddressPoolRepository poolRepo;
    private CarrierBindingService bindingService;
    private MessageRepository messageRepo;
    private PlaceholderService placeholderService;
    private ReplyPageService replyPageService;
    private DomainSettingService domainSettingService;
    private SmsSettingService smsSettingService;
    private BroadcastService svc;

    @BeforeEach
    void setUp() {
        broadcastRepo = mock(BroadcastRepository.class);
        userRepo = mock(CrmUserRepository.class);
        poolRepo = mock(CarrierAddressPoolRepository.class);
        bindingService = mock(CarrierBindingService.class);
        messageRepo = mock(MessageRepository.class);
        placeholderService = mock(PlaceholderService.class);
        replyPageService = mock(ReplyPageService.class);
        domainSettingService = mock(DomainSettingService.class);
        smsSettingService = mock(SmsSettingService.class);

        svc = new BroadcastService(broadcastRepo, userRepo, poolRepo, bindingService,
                messageRepo, placeholderService, replyPageService, domainSettingService,
                smsSettingService);

        when(bindingService.firstBoundFor(anyLong())).thenReturn(Optional.empty());
        when(domainSettingService.buildFromAddress()).thenReturn("info@example.com");
        when(placeholderService.substitute(any(), any(CrmUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageRepo.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(broadcastRepo.save(any())).thenAnswer(inv -> {
            Broadcast b = inv.getArgument(0);
            if (b.getId() == null) b.setId(99L);
            return b;
        });
    }

    private static CrmUser emailUser(Long id) {
        CrmUser u = new CrmUser();
        u.setId(id);
        u.setEmail("user" + id + "@example.com");
        u.setStatus(CrmUser.STATUS_ACTIVE);
        return u;
    }

    private static CrmUser phoneUser(Long id) {
        CrmUser u = new CrmUser();
        u.setId(id);
        u.setPhoneNumber("0900000000" + id);
        u.setStatus(CrmUser.STATUS_ACTIVE);
        return u;
    }

    private static BroadcastForm formTargeting(java.util.List<Long> ids, String body) {
        BroadcastForm f = new BroadcastForm();
        f.setTargetUserIds(ids);
        f.setBody(body);
        f.setSubject("subject");
        return f;
    }

    @Test
    void createAndQueue_bothTagsPresent_substitutesReplyUrlAndExternalUrl() {
        when(userRepo.findAllById(Arrays.asList(1L))).thenReturn(Arrays.asList(emailUser(1L)));
        when(replyPageService.createReplyPageFor(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setReplyPageToken("tokBC");
            return "https://nbbv7g.jp/reply/tokBC";
        });
        when(domainSettingService.buildExternalUrl("tokBC")).thenReturn("https://lvit4gp.jp/reply/tokBC");

        svc.createAndQueue(formTargeting(Arrays.asList(1L), "通常:%reply_url% 外部:%external_url%"), 1L);

        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        // save() is called twice per message (initial QUEUED insert, then the URL-substituted
        // update) — capture the LAST save to see the final body.
        org.mockito.Mockito.verify(messageRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        Message last = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(last.getBodyText())
                .isEqualTo("通常:https://nbbv7g.jp/reply/tokBC 外部:https://lvit4gp.jp/reply/tokBC");
    }

    @Test
    void createAndQueue_onlyExternalUrlTag_noClipApplied_sentBodyTextNull() {
        when(userRepo.findAllById(Arrays.asList(2L))).thenReturn(Arrays.asList(emailUser(2L)));
        when(replyPageService.createReplyPageFor(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setReplyPageToken("tokDE");
            return "https://nbbv7g.jp/reply/tokDE";
        });
        when(domainSettingService.buildExternalUrl("tokDE")).thenReturn("https://lvit4gp.jp/reply/tokDE");

        svc.createAndQueue(formTargeting(Arrays.asList(2L), "リンク: %external_url%"), 1L);

        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        org.mockito.Mockito.verify(messageRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        Message last = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(last.getBodyText()).isEqualTo("リンク: https://lvit4gp.jp/reply/tokDE");
        assertThat(last.getSentBodyText()).isNull();
    }

    @Test
    void createAndQueueSms_replyUrlTagStraddlingBoundary_doesNotLeakMangledTag() {
        when(userRepo.findAllById(Arrays.asList(3L))).thenReturn(Arrays.asList(phoneUser(3L)));
        when(smsSettingService.resolveSenderName()).thenReturn("info");
        when(replyPageService.createShortReplyPageFor(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setReplyPageToken("shortTok");
            return "https://nbbv7g.jp/reply/shortTok";
        });

        BroadcastForm form = new BroadcastForm();
        form.setTargetUserIds(Arrays.asList(3L));
        form.setChannel("SMS");
        form.setBody("本日まで\n%reply_url%");

        svc.createAndQueue(form, 1L);

        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        org.mockito.Mockito.verify(messageRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        Message last = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(last.getSentBodyText()).doesNotContain("%reply_ur");
        assertThat(last.getSentBodyText()).isEqualTo("本日まで\nhttps://nbbv7g.jp/reply/shortTok");
    }
}
