package com.getjobs.worker.manager;

import com.getjobs.application.service.BossAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BossInboxSelectionTest {
    private final PlaywrightManager manager = mock(PlaywrightManager.class);
    private final BossBrowserSessions browsers = mock(BossBrowserSessions.class);
    private final BossAccountService accounts = mock(BossAccountService.class);

    @Test void opensOnlyTheSelectedClosedAccountAndWaitsForItsLogin() throws Exception {
        when(accounts.list()).thenReturn(List.of(new BossAccountService.Account(1, "默认账号", true),
                new BossAccountService.Account(2, "示例账号", false)));
        when(manager.tryRunOnPlaywright(any())).thenAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return true;
        });
        when(browsers.ready(2, false)).thenAnswer(ignored -> {
            when(browsers.isOpen(2)).thenReturn(true);
            return null;
        });
        var inbox = new BossInboxService(manager, browsers, accounts);

        assertThrows(ResponseStatusException.class, () -> inbox.start(List.of()));
        assertThrows(ResponseStatusException.class, () -> inbox.start(List.of(3L)));
        assertFalse(inbox.snapshot().enabled());
        inbox.start(List.of(2L));
        assertEquals(List.of(2L), inbox.snapshot().selectedAccountIds());
        assertEquals(List.of("offline", "offline"), inbox.snapshot().accounts().stream()
                .map(BossInboxService.AccountUnread::state).toList());
        assertTrue(inbox.snapshot().accounts().get(1).browserOpen());
        inbox.schedule();
        verify(browsers).ready(2, false);
        verify(browsers, never()).ready(1, false);
        verify(browsers, never()).openedPage(1);
    }
}
