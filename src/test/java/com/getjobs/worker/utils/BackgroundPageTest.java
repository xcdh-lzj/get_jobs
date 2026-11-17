package com.getjobs.worker.utils;

import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackgroundPageTest {
    private final BrowserContext context = mock(BrowserContext.class);
    private final Page source = mock(Page.class);
    private final Page detail = mock(Page.class);
    private final CDPSession session = mock(CDPSession.class);
    private final JsonObject targetInfo = new JsonObject();

    @BeforeEach
    void setup() {
        when(source.context()).thenReturn(context);
        when(context.newCDPSession(source)).thenReturn(session);
        JsonObject info = new JsonObject();
        info.add("targetInfo", targetInfo);
        when(session.send("Target.getTargetInfo")).thenReturn(info);
        when(session.send(eq("Target.createTarget"), any(JsonObject.class))).thenAnswer(invocation -> {
            JsonObject params = invocation.getArgument(1);
            when(detail.url()).thenReturn(params.get("url").getAsString());
            JsonObject result = new JsonObject();
            result.addProperty("targetId", "created-target");
            return result;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createsBackgroundTabInSourceContextAndIgnoresOtherPages(boolean incognito) {
        if (incognito) targetInfo.addProperty("browserContextId", "source-context");
        Page otherPlatform = mock(Page.class);
        when(otherPlatform.url()).thenReturn("about:blank");
        when(context.pages()).thenReturn(List.of(otherPlatform, source, detail));
        doAnswer(invocation -> {
            assertTrue(((BooleanSupplier) invocation.getArgument(0)).getAsBoolean());
            return null;
        }).when(context).waitForCondition(any(), any());

        assertSame(detail, PlaywrightUtil.newBackgroundPage(source));

        verify(session).send(eq("Target.createTarget"), argThat(params ->
                params.get("background").getAsBoolean()
                        && (incognito ? "source-context".equals(params.get("browserContextId").getAsString())
                                      : !params.has("browserContextId"))));
        verify(context, never()).newPage();
        verify(detail, never()).bringToFront();
        verify(otherPlatform, never()).close();
        verify(session).detach();
    }

    @Test
    void closesOnlyCreatedTargetWhenPageDoesNotBecomeAvailable() {
        TimeoutError timeout = new TimeoutError("Page did not appear");
        doThrow(timeout).when(context).waitForCondition(any(), any());

        assertSame(timeout, assertThrows(TimeoutError.class,
                () -> PlaywrightUtil.newBackgroundPage(source)));

        verify(session).send(eq("Target.closeTarget"), argThat(params ->
                "created-target".equals(params.get("targetId").getAsString())));
        verify(session).detach();
        verify(context, never()).newPage();
    }

    @Test
    void doesNotFallBackToForegroundTabWhenCreationFails() {
        when(session.send(eq("Target.createTarget"), any(JsonObject.class)))
                .thenThrow(new PlaywrightException("CDP failed"));

        assertThrows(PlaywrightException.class, () -> PlaywrightUtil.newBackgroundPage(source));

        verify(context, never()).newPage();
        verify(session, never()).send(eq("Target.closeTarget"), any(JsonObject.class));
        verify(session).detach();
    }
}
