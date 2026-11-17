package com.getjobs.application.controller;

import com.getjobs.application.service.BossAccountService;
import com.getjobs.worker.manager.BossBrowserSessions;
import com.getjobs.worker.service.BossJobService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossAccountControllerTest {
    @Test void confirmingLoginLoadsSavedSessionWithoutOpeningInteractiveWindowAndRejectsBusyQueue() {
        var accounts=mock(BossAccountService.class);
        var browsers=mock(BossBrowserSessions.class);
        var jobs=mock(BossJobService.class);
        var controller=new BossAccountController(accounts,browsers,jobs);
        when(browsers.loggedIn(2L)).thenReturn(true);
        assertEquals(true,controller.saveLogin(2).get("isLoggedIn"));
        var order=inOrder(browsers);
        order.verify(browsers).ready(2,false);
        order.verify(browsers).save(2);
        order.verify(browsers).loggedIn(2);
        when(jobs.isRunning()).thenReturn(true);
        assertThrows(ResponseStatusException.class,() -> controller.saveLogin(2));
        verify(browsers,times(1)).ready(anyLong(),anyBoolean());
    }
    @Test void queueCanLazilyRestoreSavedAccountsInsteadOfRequiringOpenLoginWindows() {
        var jobs=mock(BossJobService.class);
        var browsers=mock(BossBrowserSessions.class);
        when(jobs.startQueue(eq(List.of(1L,2L)),any())).thenReturn(true);
        var response=new BossController(jobs,browsers).startQueue(new BossController.QueueRequest(List.of(1L,2L)));
        assertTrue(response.getStatusCode().is2xxSuccessful());
        verify(browsers,never()).loggedIn(anyLong());
        verify(jobs).startQueue(eq(List.of(1L,2L)),any());
    }
}
