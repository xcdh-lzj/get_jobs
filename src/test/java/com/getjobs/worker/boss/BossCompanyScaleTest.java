package com.getjobs.worker.boss;

import com.getjobs.worker.boss.review.BossCandidateCollector;
import com.getjobs.worker.boss.review.BossReviewJob;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossCompanyScaleTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void listAndDomDuplicatesKeepTheCompanySize(boolean sizeArrivesFirst) {
        var listing = mock(Page.class);
        var fromList = candidate("100-499人");
        var fromDom = candidate("");
        try (var collector = new BossCandidateCollector(listing, "模拟搜索")) {
            collector.add(sizeArrivesFirst ? fromList : fromDom);
            collector.add(sizeArrivesFirst ? fromDom : fromList);
            assertEquals(1, collector.jobs().size());
            assertEquals("100-499人", collector.jobs().getFirst().companyScale());
        }
    }

    @Test void detailReadsCompanySizeFromTheCompanyBasicsPanelWhenTheResponseIsBlank() {
        var detail = loadDetail("", "", List.of("模拟公司", "未融资", "１００－４９９人", "软件服务"), "模拟职位描述");
        assertEquals("", detail.error());
        assertEquals("100-499人", detail.job().companyScale());
    }

    @Test void availableDetailDataSurvivesMissingDescriptionAndConflictingPanelSizesRemainUnknown() {
        var incompleteDetail = loadDetail("", "", List.of("10000人以上"), "");
        assertFalse(incompleteDetail.error().isBlank());
        assertEquals("10000人以上", incompleteDetail.job().companyScale());

        var conflictingSizes = loadDetail("", "", List.of("20-99人", "500-999人"), "模拟职位描述");
        assertEquals("", conflictingSizes.job().companyScale());
    }

    @Test void blankDetailSizeKeepsListSizeAndPresentDetailSizeTakesPrecedence() {
        assertEquals("20-99人", loadDetail("20-99人", "  ", List.of(), "模拟职位描述").job().companyScale());
        assertEquals("500-999人", loadDetail("20-99人", "500-999人", List.of(), "模拟职位描述").job().companyScale());
    }

    private static BossReviewJob candidate(String scale) {
        return BossCandidateCollector.fromList(new JSONObject()
                .put("encryptJobId", "fixture-company-scale").put("jobName", "模拟岗位")
                .put("brandName", "模拟公司").put("brandScaleName", scale).put("salaryDesc", "20-25K"), "模拟搜索");
    }

    private static BossReviewDetails.Detail loadDetail(String listScale, String apiScale, List<String> panel, String jd) {
        var listing = mock(Page.class);
        var detail = mock(Page.class);
        var response = mock(Response.class);
        var absent = mock(Locator.class);
        var candidate = candidate(listScale);
        var listener = new AtomicReference<Consumer<Response>>();
        doAnswer(call -> { listener.set(call.getArgument(0)); return null; }).when(detail).onResponse(any());
        when(detail.url()).thenReturn(candidate.detailUrl());
        when(detail.getByText(any(Pattern.class))).thenReturn(absent);
        when(detail.locator(".job-sec-text")).thenReturn(absent);
        when(detail.evaluate(contains("const heading = '公司基本信息'"))).thenReturn(panel);
        when(response.url()).thenReturn("https://www.zhipin.com/wapi/zpgeek/job/detail.json");
        when(response.status()).thenReturn(200);
        when(response.text()).thenReturn(new JSONObject().put("zpData", new JSONObject()
                .put("jobInfo", new JSONObject().put("encryptId", candidate.id()).put("postDescription", jd))
                .put("brandComInfo", new JSONObject().put("brandName", "模拟公司").put("scaleName", apiScale))).toString());
        when(detail.navigate(eq(candidate.detailUrl()), any(Page.NavigateOptions.class))).thenAnswer(call -> {
            listener.get().accept(response);
            return response;
        });
        try (var playwright = mockStatic(PlaywrightUtil.class)) {
            playwright.when(() -> PlaywrightUtil.newBackgroundPage(listing)).thenReturn(detail);
            return BossReviewDetails.load(listing, candidate, false);
        }
    }
}
