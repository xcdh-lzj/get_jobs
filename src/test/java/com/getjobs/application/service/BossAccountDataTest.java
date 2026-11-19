package com.getjobs.application.service;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.getjobs.application.entity.*;
import com.getjobs.application.mapper.*;
import com.getjobs.application.service.BossService.HistoryFilters;
import com.getjobs.application.service.BossService.HistorySort;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BossAccountDataTest {
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private SqlSession session;
    private BossService boss;
    private BossAccountService accounts;
    private BossAccountLocks locks;
    private BossCitySettingsService citySettings;
    private BossHrSettingsService hrSettings;

    @BeforeEach void setup() throws Exception {
        var source=new SQLiteDataSource(); source.setUrl("jdbc:sqlite:"+directory.resolve("test.db"));
        jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE boss_config (id INTEGER PRIMARY KEY AUTOINCREMENT, debugger INTEGER, wait_time INTEGER, keywords TEXT, city_code TEXT, industry TEXT, job_type TEXT, experience TEXT, degree TEXT, salary TEXT, scale TEXT, stage TEXT, say_hi TEXT, expected_salary_min INTEGER, expected_salary_max INTEGER, enable_ai INTEGER, send_img_resume INTEGER, allow_repeat_chat INTEGER, repeat_chat_messages TEXT, filter_dead_hr INTEGER, dead_status TEXT, created_at TEXT, updated_at TEXT)");
        jdbc.execute("CREATE TABLE boss_data (id INTEGER PRIMARY KEY AUTOINCREMENT, encrypt_id TEXT, encrypt_user_id TEXT, company_name TEXT, job_name TEXT, salary TEXT, location TEXT, experience TEXT, degree TEXT, hr_name TEXT, hr_position TEXT, hr_active_status TEXT, delivery_status TEXT, job_description TEXT, job_url TEXT, recruitment_status TEXT, company_address TEXT, industry TEXT, introduce TEXT, financing_stage TEXT, company_scale TEXT, created_at TEXT, updated_at TEXT)");
        jdbc.execute("CREATE TABLE boss_blacklist(id INTEGER PRIMARY KEY AUTOINCREMENT,type TEXT,value TEXT,created_at TEXT,updated_at TEXT)");
        jdbc.execute("CREATE TABLE ai(id INTEGER PRIMARY KEY,introduce TEXT,prompt TEXT)");
        jdbc.execute("CREATE TABLE boss_option(id INTEGER PRIMARY KEY,type TEXT,name TEXT,code TEXT,sort_order INTEGER,created_at TEXT,updated_at TEXT)");
        jdbc.execute("INSERT INTO boss_option(type,name,code) VALUES('city','广州','101280100'),('city','深圳','101280600'),('city','上海','101020100'),('city','全国','100010000'),('city','不限','0')");
        jdbc.execute("CREATE TABLE cookie(id INTEGER PRIMARY KEY,platform TEXT,cookie_value TEXT)");
        jdbc.update("INSERT INTO ai VALUES(1,'原有个人介绍','原有提示词')");
        jdbc.update("INSERT INTO cookie VALUES(1,'liepin','private-login-fixture')");
        jdbc.update("INSERT INTO boss_config(id,keywords,city_code,say_hi,allow_repeat_chat) VALUES(1,'Java','上海','原有招呼语',1)");
        var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.afterPropertiesSet();
        var sql=factory.getObject();
        sql.getConfiguration().addMapper(BossConfigMapper.class);
        sql.getConfiguration().addMapper(BossJobDataMapper.class);
        sql.getConfiguration().addMapper(BlacklistMapper.class);
        sql.getConfiguration().addMapper(BossOptionMapper.class);
        session=sql.openSession(true);
        boss=new BossService(session.getMapper(BossOptionMapper.class),null,session.getMapper(BossConfigMapper.class),session.getMapper(BlacklistMapper.class),session.getMapper(BossJobDataMapper.class),source);
        boss.ensureRepeatChatColumn();
        locks=new BossAccountLocks();
        citySettings=new BossCitySettingsService(jdbc,new com.fasterxml.jackson.databind.ObjectMapper(),locks);citySettings.migrate();
        hrSettings=new BossHrSettingsService(jdbc,new com.fasterxml.jackson.databind.ObjectMapper(),locks);hrSettings.migrate();
        accounts=new BossAccountService(jdbc,boss,locks,citySettings,hrSettings);
        accounts.migrate();
    }
    @AfterEach void close() { BossAccountContext.clear(); if (session!=null) session.close(); }

    @Test void customSalaryPersistsClearedBoundsCopiesPerAccountAndValidatesBeforeOtherChanges() throws Exception {
        jdbc.execute("INSERT INTO boss_option(type,name,code) VALUES('salary','不限','0'),('salary','10-20K','405'),('salary','20-50K','406'),('salary','50K以上','407')");
        jdbc.update("UPDATE boss_config SET salary='[10-20K]',expected_salary_min=15,expected_salary_max=25");
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.getjobs.application.controller.BossConfigController(boss,new BossResumeImageService(),citySettings,hrSettings)).build();
        var put=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/boss/config").contentType("application/json");
        mvc.perform(put.content("{\"salaryPolicy\":{\"mode\":\"custom\",\"minMonthlyK\":12.5,\"maxMonthlyK\":18.5}}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals(List.of("405"),boss.loadBossConfig().getSalary());assertEquals(12.5,boss.loadBossConfig().getSalarySelection().minimumK());
        long second=accounts.create("第二账号",1L).id();
        try(var scope=BossAccountContext.use(second)) {
            assertEquals(12.5,com.getjobs.worker.boss.BossSalaryPolicy.of(boss.getFirstConfig()).minMonthlyK());
            mvc.perform(put.content("{\"salaryPolicy\":{\"mode\":\"custom\",\"minMonthlyK\":15,\"maxMonthlyK\":null}}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            assertNull(boss.loadBossConfig().getSalarySelection().maximumK());assertTrue(boss.loadBossConfig().getSalary().contains("407"));
        }
        assertEquals(18.5,boss.loadBossConfig().getSalarySelection().maximumK());
        mvc.perform(put.content("{\"salaryPolicy\":{\"mode\":\"custom\",\"minMonthlyK\":null,\"maxMonthlyK\":20}}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertNull(boss.loadBossConfig().getSalarySelection().minimumK());
        mvc.perform(put.content("{\"sayHi\":\"保留自定义月薪\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals("custom",com.getjobs.worker.boss.BossSalaryPolicy.of(boss.getFirstConfig()).mode());assertEquals(20.0,boss.loadBossConfig().getSalarySelection().maximumK());
        mvc.perform(put.content("{\"keywords\":\"不应保存\",\"salaryPolicy\":{\"mode\":\"custom\",\"minMonthlyK\":30,\"maxMonthlyK\":20}}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertEquals("Java",boss.getFirstConfig().getKeywords());
        boss.ensureRepeatChatColumn();assertEquals(20.0,boss.loadBossConfig().getSalarySelection().maximumK());
        assertTrue(Files.exists(directory.resolve("test.db.before-boss-salary-policy.bak")));
    }

    @Test void salaryUiSelectionControlsSavedLimitsRuntimeAndEachAccountIndependently() throws Exception {
        jdbc.execute("INSERT INTO boss_option(type,name,code) VALUES('salary','不限','0'),('salary','3-5K','403'),('salary','10-20K','405'),('salary','20-50K','406')");
        jdbc.update("UPDATE boss_config SET salary='[10-20K]',expected_salary_min=15,expected_salary_max=25");
        assertEquals(10.0,boss.loadBossConfig().getSalarySelection().minimumK());
        assertEquals(java.util.List.of(10,20),boss.loadBossConfig().getExpectedSalary());
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.getjobs.application.controller.BossConfigController(boss,new BossResumeImageService(),citySettings,hrSettings)).build();
        var put=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/boss/config").contentType("application/json");
        mvc.perform(put.content("{\"salary\":\"[405]\",\"expectedSalaryMin\":15,\"expectedSalaryMax\":25}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals("[10-20K]",boss.getFirstConfig().getSalary());assertEquals(10,boss.getFirstConfig().getExpectedSalaryMin());assertEquals(20,boss.getFirstConfig().getExpectedSalaryMax());
        mvc.perform(put.content("{\"expectedSalaryMin\":15,\"expectedSalaryMax\":25}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals(10,boss.getFirstConfig().getExpectedSalaryMin());
        long second=accounts.create("第二账号",1L).id();
        try(var scope=BossAccountContext.use(second)) {
            mvc.perform(put.content("{\"salary\":\"[403,406]\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            var selected=boss.loadBossConfig().getSalarySelection();assertFalse(selected.overlaps(12,18));assertTrue(selected.overlaps(25,30));
        }
        assertEquals("[10-20K]",boss.getFirstConfig().getSalary());assertTrue(boss.loadBossConfig().getSalarySelection().overlaps(12,18));
        mvc.perform(put.content("{\"salary\":\"[999]\",\"keywords\":\"不应保存\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertEquals("Java",boss.getFirstConfig().getKeywords());assertEquals("[10-20K]",boss.getFirstConfig().getSalary());
        mvc.perform(put.content("{\"salary\":\"[]\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertTrue(boss.loadBossConfig().getSalarySelection().unrestricted());assertEquals(0,boss.getFirstConfig().getExpectedSalaryMin());assertEquals(0,boss.getFirstConfig().getExpectedSalaryMax());
    }

    @Test void citiesAndThresholdsSaveWithoutOverwritingOtherFiltersAndCopyIndependently() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.getjobs.application.controller.BossConfigController(boss,new BossResumeImageService(),citySettings,hrSettings)).build();
        var put=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/boss/config").contentType("application/json");
        mvc.perform(put.content("{\"cityCode\":\"[101280600,101280100,101280600]\",\"citySwitchPolicy\":{\"enabled\":true,\"windowSize\":20,\"maxNewJobs\":1}}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals("[深圳,广州]",boss.getFirstConfig().getCityCode());assertEquals("Java",boss.getFirstConfig().getKeywords());assertEquals("原有招呼语",boss.getFirstConfig().getSayHi());
        assertEquals(20,citySettings.policy().windowSize());
        long second=accounts.create("第二账号",1L).id();
        try(var scope=BossAccountContext.use(second)){assertEquals("[深圳,广州]",boss.getFirstConfig().getCityCode());assertEquals(20,citySettings.policy().windowSize());citySettings.save(new com.getjobs.worker.boss.BossCityPolicy(false,40,2));}
        assertEquals(20,citySettings.policy().windowSize());
        mvc.perform(put.content("{\"cityCode\":\"广州\",\"citySwitchPolicy\":{\"enabled\":true,\"windowSize\":5,\"maxNewJobs\":5}}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertEquals("[深圳,广州]",boss.getFirstConfig().getCityCode());assertEquals(20,citySettings.policy().windowSize());
        mvc.perform(put.content("{\"cityCode\":\"[全国,广州]\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertEquals("[深圳,广州]",boss.getFirstConfig().getCityCode());
        try(var scope=BossAccountContext.use(second)){assertEquals(40,citySettings.policy().windowSize());locks.reserve(java.util.List.of(second));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->citySettings.save(com.getjobs.worker.boss.BossCityPolicy.defaults()));locks.release();}
        assertTrue(Files.isRegularFile(directory.resolve("test.db.before-boss-multi-city.bak")));citySettings.migrate();assertEquals("[深圳,广州]",boss.getFirstConfig().getCityCode());
    }

    @Test void hrDaysDefaultToThreeSavePerAccountAndCopyWithoutChangingOtherFilters() throws Exception {
        assertEquals(3,hrSettings.policy().maxDays());
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.getjobs.application.controller.BossConfigController(boss,new BossResumeImageService(),citySettings,hrSettings)).build();
        var put=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/boss/config").contentType("application/json");
        mvc.perform(put.content("{\"filterDeadHr\":1,\"hrActivityPolicy\":{\"maxDays\":5}}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals(5,hrSettings.policy().maxDays());assertEquals("Java",boss.getFirstConfig().getKeywords());assertEquals("上海",boss.getFirstConfig().getCityCode());
        long second=accounts.create("第二账号",1L).id();
        try(var scope=BossAccountContext.use(second)){assertEquals(5,hrSettings.policy().maxDays());hrSettings.save(new com.getjobs.worker.boss.BossHrActivityPolicy(7));}
        assertEquals(5,hrSettings.policy().maxDays());
        mvc.perform(put.content("{\"keywords\":\"不应保存\",\"filterDeadHr\":0,\"hrActivityPolicy\":{\"maxDays\":0}}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertEquals("Java",boss.getFirstConfig().getKeywords());assertEquals(1,boss.getFirstConfig().getFilterDeadHr());assertEquals(5,hrSettings.policy().maxDays());
        locks.reserve(java.util.List.of(1L));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->hrSettings.save(new com.getjobs.worker.boss.BossHrActivityPolicy(30)));locks.release();
        assertTrue(Files.isRegularFile(directory.resolve("test.db.before-boss-hr-activity.bak")));hrSettings.migrate();assertEquals(5,hrSettings.policy().maxDays());
    }

    @Test void migrationIsRepeatableAndPreservesLegacyDataAndOtherPlatforms() {
        assertTrue(Files.isRegularFile(directory.resolve("test.db.before-boss-accounts.bak")));
        assertEquals("原有个人介绍",accounts.profile(1).introduce());
        accounts.saveProfile(1,"用户改过的介绍","");
        accounts.migrate(); accounts.run();
        assertEquals("用户改过的介绍",accounts.profile(1).introduce());
        assertEquals("原有招呼语",boss.getFirstConfig().getSayHi());
        assertEquals(1,boss.getFirstConfig().getAllowRepeatChat());
        assertEquals(1L,boss.getFirstConfig().getAccountId());
        assertEquals("private-login-fixture",jdbc.queryForObject("SELECT cookie_value FROM cookie WHERE platform='liepin'",String.class));
    }
    @Test void configurationsBlacklistsAndProfilesBelongToTheirOwnAccount() {
        long id=accounts.create("第二账号",1L).id();
        assertEquals("",accounts.profile(id).introduce());
        try (var scope=BossAccountContext.use(id)) {
            assertEquals(id,boss.getFirstConfig().getAccountId());
            var patch=new BossConfigEntity(); patch.setId(1L); patch.setAccountId(1L); patch.setSayHi("第二账号招呼语");
            boss.saveOrUpdateFirstSelective(patch);
            assertTrue(boss.addBlacklist("company","第二账号公司"));
            assertEquals(1,boss.getAllBlacklist().size());
        }
        assertEquals("原有招呼语",boss.getFirstConfig().getSayHi());
        assertTrue(boss.getAllBlacklist().isEmpty());
        assertEquals(1L,BossAccountContext.id());
        try (var scope=BossAccountContext.use(id)) { assertEquals("第二账号招呼语",boss.getFirstConfig().getSayHi()); }
    }

    @Test void legacyCompanyBlacklistWorksWithTheSameNormalizationUsedByCopyDeduplication() {
        jdbc.update("INSERT INTO boss_blacklist(account_id,type,value) VALUES(1,'boss',' 旧版公司 '),(1,'company','公司B'),(1,'company',NULL),(1,'company',' ')");
        assertEquals(java.util.Set.of("旧版公司","公司B"),boss.getBlackCompanies());
        try(var scope=BossAccountContext.use(accounts.create("第二账号",null).id())){assertTrue(boss.getBlackCompanies().isEmpty());}
    }

    @Test void oldGlobalBlacklistIndexBecomesAccountScopedWithoutChangingExistingRows() throws Exception {
        jdbc.execute("CREATE UNIQUE INDEX idx_blacklist_type_value ON boss_blacklist(type,value)");
        assertTrue(boss.addBlacklist("company","两个账号共用的屏蔽公司"));
        var before=jdbc.queryForList("SELECT * FROM boss_blacklist");accounts.migrate();
        assertEquals(before,jdbc.queryForList("SELECT * FROM boss_blacklist"));
        assertEquals(java.util.List.of("account_id","type","value"),jdbc.query("PRAGMA index_info('idx_blacklist_type_value')",(r,n)->r.getString("name")));
        long second=accounts.create("第二账号",null).id();
        var copies=new BossBlacklistCopyService(jdbc,accounts,locks,new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        try(var scope=BossAccountContext.use(second)) {
            assertEquals(1,copies.copy(1,java.util.List.of("company")).copiedCount());
            assertFalse(boss.addBlacklist("company","两个账号共用的屏蔽公司"));
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM boss_blacklist",Integer.class));
        var backup=directory.resolve("test.db.before-boss-blacklist-copy.bak");assertTrue(Files.isRegularFile(backup));
        var size=Files.size(backup);accounts.migrate();assertEquals(size,Files.size(backup));
    }
    @Test void sameJobCanHaveIndependentDeliveryStatusesAndStatistics() {
        long second=accounts.create("第二账号",null).id();
        for (long id : new long[]{1,second}) try (var scope=BossAccountContext.use(id)) {
            assertFalse(boss.existsBossJob("same-job","same-recruiter"));
            var job=new BossJobDataEntity(); job.setEncryptId("same-job"); job.setEncryptUserId("same-recruiter");
            job.setJobName("Java 开发"); job.setCompanyName("示例公司"); job.setDeliveryStatus("未投递");
            boss.insertBossJob(job);
        }
        var sentAt = LocalDateTime.of(2026, 11, 18, 15, 8, 7);
        boss.updateDeliveryStatus("same-job", "same-recruiter", "已投递", "已完成招呼语发送流程", sentAt);
        boss.updateDeliveryStatus("same-job", "same-recruiter", "已过滤", "再次筛选命中黑名单", null);
        assertEquals(1,boss.getBossStats().kpi.delivered);
        var sentFilter = new HistoryFilters(List.of("已投递"), null, null, null, null, null, null, false, null, null);
        var sent = boss.listBossJobs(sentFilter, HistorySort.RECORDED_DESC, 1, 20).items.getFirst();
        assertEquals(sentAt, sent.getDeliveredAt());
        assertNotNull(sent.getProcessedAt());
        assertEquals("已完成招呼语发送流程", sent.getDeliveryReason());
        try (var scope=BossAccountContext.use(second)) {
            assertEquals(0,boss.getBossStats().kpi.delivered);
            assertEquals(1,boss.getBossStats().kpi.pending);
            var rows=boss.listBossJobs(unfilteredHistory(), HistorySort.RECORDED_DESC, 1, 20);
            assertEquals(1,rows.total); assertEquals("未投递",rows.items.getFirst().getDeliveryStatus());
            assertNull(rows.items.getFirst().getDeliveredAt());
            assertNotNull(rows.items.getFirst().getProcessedAt());
            assertNull(rows.items.getFirst().getDeliveryReason());
            boss.ensureBossDataColumnOrder();
            assertEquals(second,rows.items.getFirst().getAccountId());
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM boss_data",Integer.class));
    }
    @Test void defaultProfileIsInheritedAfterFreshInstallDefaultsOnlyOnce() {
        jdbc.update("UPDATE boss_accounts SET introduce='',prompt='',profile_seeded=0 WHERE id=1");
        accounts.run(); assertEquals("原有个人介绍",accounts.profile(1).introduce());
        accounts.saveProfile(1,"",""); accounts.run(); assertEquals("",accounts.profile(1).introduce());
    }

    @Test void precisionListCollectionRetainsExistingJobDetailsAndSuccessfulDelivery() {
        var existing=new BossJobDataEntity();existing.setEncryptId("same-job");existing.setJobName("原岗位");
        existing.setCompanyName("原公司");existing.setLocation("广州");existing.setSalary("20-30K");
        existing.setJobDescription("完整原始JD");existing.setHrPosition("技术负责人");existing.setDeliveryStatus("已投递");boss.insertBossJob(existing);
        var sentAt = LocalDateTime.of(2026, 11, 18, 15, 8, 7, 950_000_000);
        var partial=new com.getjobs.worker.boss.review.BossReviewJob("same-job","更新后的岗位名","","","","","","","","","https://www.zhipin.com/job_detail/same-job.html","","100-499人");
        boss.recordPrecisionJob(partial, "已投递", "已完成招呼语发送流程", sentAt);
        var resultTime = session.getMapper(BossJobDataMapper.class).selectById(existing.getId()).getProcessedAt();
        boss.recordPrecisionJob(partial, "已过滤", "再次筛选命中黑名单", null);
        var saved=session.getMapper(BossJobDataMapper.class).selectById(existing.getId());
        assertEquals("更新后的岗位名",saved.getJobName());assertEquals("原公司",saved.getCompanyName());
        assertEquals("完整原始JD",saved.getJobDescription());assertEquals("技术负责人",saved.getHrPosition());
        assertEquals("20-30K",saved.getSalary());assertEquals("已投递",saved.getDeliveryStatus());
        assertEquals("100-499人", saved.getCompanyScale());
        assertEquals("已完成招呼语发送流程", saved.getDeliveryReason());
        assertEquals(sentAt.withNano(0), saved.getDeliveredAt());
        assertNotNull(resultTime);
        assertEquals(resultTime, saved.getProcessedAt());
        long second=accounts.create("第二账号",null).id();
        try(var scope=BossAccountContext.use(second)) {boss.recordPrecisionJob(partial,"未投递","未配置招呼语",null);}
        assertEquals("已投递",session.getMapper(BossJobDataMapper.class).selectById(existing.getId()).getDeliveryStatus());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM boss_data",Integer.class));
    }

    @Test void futureFilteredSkippedAndFailedResultsStoreTheirProcessingTime() {
        var jobs = session.getMapper(BossJobDataMapper.class);
        var recent = LocalDateTime.now().withNano(0);
        for (String status : List.of("已过滤", "未投递", "投递失败")) {
            var row = new BossJobDataEntity();
            row.setEncryptId("legacy-fixture-" + status);
            row.setDeliveryStatus("未投递");
            boss.insertBossJob(row);
            jdbc.update("UPDATE boss_data SET processed_at='2026-10-01T12:00:00' WHERE id=?", row.getId());
            boss.updateDeliveryStatus(row.getEncryptId(), null, status, "模拟处理结果", null);
            var saved = jobs.selectById(row.getId());
            assertEquals(status, saved.getDeliveryStatus());
            assertNull(saved.getDeliveredAt());
            assertFalse(saved.getProcessedAt().isBefore(recent));
            assertEquals(0, saved.getProcessedAt().getNano());

            var reviewJob = new com.getjobs.worker.boss.review.BossReviewJob(
                    "precision-fixture-" + row.getId(), "模拟岗位", "", "", "", "", "", "", "",
                    "模拟职责", "https://www.zhipin.com/job_detail/precision-fixture-" + row.getId() + ".html", "");
            boss.recordPrecisionJob(reviewJob, status, "模拟审核结果", null);
            var precision = jobs.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BossJobDataEntity>()
                    .eq("encrypt_id", reviewJob.id()));
            assertEquals(status, precision.getDeliveryStatus());
            assertNull(precision.getDeliveredAt());
            assertFalse(precision.getProcessedAt().isBefore(recent));
        }
    }

    @Test void legacyJobHistoryRestoresOnlyMatchingReviewReasonsAndKeepsActualTimeUnknown() {
        jdbc.execute("ALTER TABLE boss_data DROP COLUMN delivery_reason");
        jdbc.execute("ALTER TABLE boss_data DROP COLUMN delivered_at");
        jdbc.execute("CREATE TABLE boss_review_runs(id TEXT, account_id INTEGER, mode TEXT)");
        jdbc.execute("CREATE TABLE boss_review_results(id INTEGER, run_id TEXT, account_id INTEGER, decision TEXT, delivery_state TEXT, result_json TEXT, created_at INTEGER)");
        jdbc.execute("INSERT INTO boss_review_runs VALUES('delivery',1,'delivery'),('sample',1,'examples'),('other',2,'delivery')");
        jdbc.execute("""
                INSERT INTO boss_data(account_id,encrypt_id,delivery_status,created_at) VALUES
                    (1,'matching','已投递','2026-11-18T11:30:00'),
                    (1,'mismatched','未投递','2026-11-18T11:30:00'),
                    (2,'matching','已投递','2026-11-18T11:30:00')
                """);
        jdbc.execute("""
                INSERT INTO boss_review_results VALUES
                    (1,'delivery',1,'pass','sent','{"job":{"id":"matching"},"reason":"负责 Agent 开发"}',10),
                    (2,'sample',1,'pass','sent','{"job":{"id":"matching"},"reason":"模拟结果"}',20),
                    (3,'delivery',1,'reject','not_sent','{"job":{"id":"mismatched"},"reason":"过滤原因"}',30),
                    (4,'other',2,'pass','not_sent','{"job":{"id":"matching"},"reason":"另一账号"}',40),
                    (5,'delivery',1,'pass','sent','invalid JSON',50)
                """);
        accounts.migrate();
        accounts.migrate();
        assertEquals(List.of("历史审核：负责 Agent 开发"), jdbc.queryForList(
                "SELECT delivery_reason FROM boss_data WHERE delivery_reason IS NOT NULL", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM boss_data WHERE delivered_at IS NOT NULL", Integer.class));
    }

    @Test void historyExportUsesTheSameAccountAndAppliedFiltersAsThePagedList() throws Exception {
        long other = accounts.create("第二账号", null).id();
        for (long account : new long[]{1, other}) try (var scope = BossAccountContext.use(account)) {
            for (int i = 0; i < 4; i++) {
                var row = new BossJobDataEntity();
                row.setCompanyName("示例公司 " + account);
                row.setJobName("Agent " + i);
                row.setSalary(i == 0 ? "30-40K" : "18-22K");
                row.setHrPosition(i == 1 ? "獵頭顾问" : "技术负责人");
                row.setDeliveryStatus("已过滤");
                row.setDeliveryReason("示例过滤原因");
                boss.insertBossJob(row);
            }
        }
        var filters = new HistoryFilters(List.of("已过滤"), null, null, null, 15.0, 25.0, "Agent", true, null, null);
        var rows = boss.listBossJobs(filters, HistorySort.RECORDED_DESC, 1, 1);
        var controller = new com.getjobs.application.controller.BossAnalyticsController(boss);
        var response = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build()
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/boss/jobs.csv")
                        .param("statuses", "已过滤").param("minK", "15").param("maxK", "25")
                        .param("keyword", "Agent").param("filterHeadhunter", "true"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeader("Content-Disposition").startsWith("attachment;"));
        String csv = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(2, rows.total);
        assertEquals(1, rows.items.size());
        assertEquals(3, csv.lines().count());
        assertTrue(csv.contains(rows.items.getFirst().getJobName()));
        assertTrue(csv.contains("示例过滤原因"));
        assertFalse(csv.contains("示例公司 " + other));
        assertFalse(csv.contains("Agent 0") || csv.contains("Agent 1"));
    }

    @Test void historyDatesIncludeBothEndsAndUseTheSameRangeForTheListStatsAndCsv() throws Exception {
        var day = LocalDate.of(2026, 11, 19);
        insertHistoryJob("前一天的岗位", "20K", day.minusDays(1).atTime(23, 59, 59));
        insertHistoryJob("晚上投递的岗位", "12K", day.atTime(23, 59, 59));
        insertHistoryJob("凌晨投递的岗位", "15K", day.atStartOfDay());
        insertHistoryJob("第二天的岗位", "20K", day.plusDays(1).atStartOfDay());
        insertHistoryJob("历史时间缺失岗位", "25K", null);
        long other = accounts.create("第二账号", null).id();
        try (var scope = BossAccountContext.use(other)) {
            insertHistoryJob("另一账号的岗位", "20K", day.atTime(12, 0));
        }

        var controller = new com.getjobs.application.controller.BossAnalyticsController(boss);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        var sameDay = new org.springframework.util.LinkedMultiValueMap<String, String>();
        sameDay.add("deliveredFrom", day.toString());
        sameDay.add("deliveredTo", day.toString());
        sameDay.add("statuses", "已投递, 未投递");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var firstPage = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/boss/list")
                        .params(sameDay).param("sort", "DELIVERED_ASC").param("size", "1"))
                .andReturn().getResponse();
        assertEquals(200, firstPage.getStatus());
        var page = mapper.readTree(firstPage.getContentAsByteArray());
        assertEquals(2, page.path("total").asInt());
        assertEquals("凌晨投递的岗位", page.path("items").get(0).path("jobName").asText());
        assertEquals("2026-11-19 00:00:00", page.path("items").get(0).path("deliveredAt").asText());
        var stats = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/boss/stats")
                        .params(sameDay)).andReturn().getResponse();
        assertEquals(200, stats.getStatus());
        assertEquals(2, mapper.readTree(stats.getContentAsByteArray()).path("kpi").path("total").asInt());
        var export = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/boss/jobs.csv")
                        .params(sameDay).param("sort", "DELIVERED_DESC")).andReturn().getResponse();
        assertEquals(200, export.getStatus());
        var csv = export.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).lines().toList();
        assertEquals(3, csv.size());
        assertTrue(csv.get(1).contains("晚上投递的岗位"));
        assertTrue(csv.get(2).contains("凌晨投递的岗位"));

        var upToDay = new HistoryFilters(null, null, null, null, null, null, null, false, null, day);
        assertEquals(3, boss.listBossJobs(upToDay, HistorySort.DELIVERED_ASC, 1, 20).total);
        var fromDay = new HistoryFilters(null, null, null, null, null, null, null, false, day, null);
        assertEquals(3, boss.listBossJobs(fromDay, HistorySort.DELIVERED_DESC, 1, 20).total);
        for (var sort : List.of(HistorySort.DELIVERED_ASC, HistorySort.DELIVERED_DESC)) {
            var records = boss.listBossJobs(unfilteredHistory(), sort, 1, 20).items;
            assertEquals("历史时间缺失岗位", records.getLast().getJobName());
        }
        for (String endpoint : List.of("/list", "/stats", "/jobs.csv")) {
            var invalid = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/boss" + endpoint)
                            .param("deliveredFrom", "2026-11-20").param("deliveredTo", "2026-11-19"))
                    .andReturn().getResponse();
            assertEquals(400, invalid.getStatus());
        }
    }

    @Test void historySalarySortComparesMonthlyMidpointsBeforePaginationAndLeavesUnknownValuesLast() {
        insertHistoryJob("较高薪资", "18-22K·14薪", null);
        insertHistoryJob("面议岗位", "面议", null);
        insertHistoryJob("较低薪资", "8000-10000元", null);
        insertHistoryJob("中间薪资", "1.2-2万", null);
        insertHistoryJob("同薪资新岗位", "16K", null);
        var filters = unfilteredHistory();
        var asc = boss.listBossJobs(filters, HistorySort.SALARY_ASC, 1, 2);
        assertEquals(5, asc.total);
        assertEquals(List.of("较低薪资", "同薪资新岗位"), asc.items.stream().map(BossJobDataEntity::getJobName).toList());
        assertEquals("示例岗位描述", asc.items.getFirst().getJobDescription());
        assertEquals(List.of("中间薪资", "较高薪资"), boss.listBossJobs(filters, HistorySort.SALARY_ASC, 2, 2)
                .items.stream().map(BossJobDataEntity::getJobName).toList());
        var desc = boss.exportBossJobs(filters, HistorySort.SALARY_DESC);
        assertEquals(List.of("较高薪资", "同薪资新岗位", "中间薪资", "较低薪资", "面议岗位"),
                desc.stream().map(BossJobDataEntity::getJobName).toList());
        assertEquals("面议岗位", boss.listBossJobs(filters, HistorySort.SALARY_ASC, 3, 2).items.getFirst().getJobName());
        var bounded = new HistoryFilters(null, null, null, null, 10.0, 18.0, null, false, null, null);
        assertEquals(List.of("同薪资新岗位", "中间薪资"), boss.exportBossJobs(bounded, HistorySort.SALARY_DESC)
                .stream().map(BossJobDataEntity::getJobName).toList());
    }

    @Test void processingTimeMigrationAndFiltersCoverEveryOutcomeAndPreserveRecoveredSendingHistory() throws Exception {
        jdbc.execute("ALTER TABLE boss_data DROP COLUMN processed_at");
        jdbc.execute("""
                INSERT INTO boss_data(account_id,job_name,delivery_status,created_at,updated_at,delivered_at) VALUES
                    (1,'示例已投递','已投递','2026-11-18T00:00:00','2026-11-20T00:05:01','2026-11-19T00:00:00'),
                    (1,'示例已过滤','已过滤','2026-11-18T00:00:00','2026-11-19T23:59:59.999999',NULL),
                    (1,'示例未投递','未投递','2026-11-19T03:25',NULL,NULL),
                    (1,'示例投递失败','投递失败','2026-11-18T00:00:00','2026-11-19T07:00:01',NULL),
                    (1,'次日示例','未投递','2026-11-20T00:00:00',NULL,NULL),
                    (2,'其他账号示例','未投递','2026-11-19T12:00:00',NULL,NULL)
                """);
        accounts.migrate();
        jdbc.update("UPDATE boss_data SET updated_at='2026-11-20T00:00:00' WHERE job_name='示例已过滤'");
        accounts.migrate();
        assertEquals("2026-11-19T00:00:00", jdbc.queryForObject(
                "SELECT delivered_at FROM boss_data WHERE job_name='示例已投递'", String.class));

        var controller = new com.getjobs.application.controller.BossAnalyticsController(boss);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        var day = new org.springframework.util.LinkedMultiValueMap<String, String>();
        day.add("processedFrom", "2026-11-19");
        day.add("processedTo", "2026-11-19");
        var page = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/boss/list")
                        .params(day).param("sort", "PROCESSED_ASC")).andReturn().getResponse();
        assertEquals(200, page.getStatus());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = mapper.readTree(page.getContentAsByteArray());
        assertEquals(4, data.path("total").asInt());
        var items = data.path("items");
        assertEquals("2026-11-19 00:00:00", items.get(0).path("processedAt").asText());
        assertEquals("未投递", items.get(1).path("deliveryStatus").asText());
        assertEquals("投递失败", items.get(2).path("deliveryStatus").asText());
        assertEquals("2026-11-19 23:59:59", items.get(3).path("processedAt").asText());
        assertTrue(items.get(3).path("deliveredAt").isNull());

        var stats = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/boss/stats")
                        .params(day)).andReturn().getResponse();
        assertEquals(200, stats.getStatus());
        assertEquals(4, mapper.readTree(stats.getContentAsByteArray()).path("kpi").path("total").asInt());

        var export = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/boss/jobs.csv")
                        .params(day).param("sort", "PROCESSED_DESC")).andReturn().getResponse();
        assertEquals(200, export.getStatus());
        var csv = export.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).lines().toList();
        assertEquals(5, csv.size());
        assertTrue(csv.getFirst().endsWith("\"处理时间\""));
        assertTrue(csv.get(1).contains("示例已过滤") && csv.get(1).endsWith("\"2026-11-19 23:59:59\""));
        assertTrue(csv.getLast().contains("示例已投递") && csv.getLast().endsWith("\"2026-11-19 00:00:00\""));
    }

    private static HistoryFilters unfilteredHistory() {
        return new HistoryFilters(null, null, null, null, null, null, null, false, null, null);
    }

    private void insertHistoryJob(String jobName, String salary, LocalDateTime deliveredAt) {
        var job = new BossJobDataEntity();
        job.setJobName(jobName);
        job.setSalary(salary);
        job.setJobDescription("示例岗位描述");
        job.setDeliveryStatus("已投递");
        job.setDeliveredAt(deliveredAt);
        boss.insertBossJob(job);
    }

    @Test void completeConfigSyncCopiesAllSettingsAndKeepsAccountIdentityAndHistory() throws Exception {
        long target = accounts.create("目标账号", null).id();
        var images = new BossResumeImageService(directory.resolve("sync-resumes"));
        var syncService = configSyncService(images);
        jdbc.update("""
                UPDATE boss_config SET wait_time=12, city_code='[上海,广州]', send_img_resume=1,
                    repeat_chat_messages='示例复聊', salary_filter_policy='{"mode":"custom","minMonthlyK":12.5}'
                WHERE account_id=1
                """);
        jdbc.update("UPDATE boss_config SET industry='[目标旧行业]',say_hi='目标旧招呼' WHERE account_id=?", target);
        citySettings.save(new com.getjobs.worker.boss.BossCityPolicy(true, 20, 2));
        hrSettings.save(new com.getjobs.worker.boss.BossHrActivityPolicy(5));
        var reviews = new BossReviewStore(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        reviews.savePolicy(new com.getjobs.worker.boss.review.BossReviewPolicy(
                true, "模拟研发方向", List.of("模拟开发"), List.of("模拟销售")));
        reviews.saveEconomy(new com.getjobs.worker.boss.review.BossReviewEconomy(false, 14, 5000));
        accounts.saveProfile(1, "来源模拟介绍", "来源模拟提示词");
        accounts.saveProfile(target, "目标旧介绍", "目标旧提示词");
        accounts.saveCookies(1, "source-login-fixture");
        accounts.saveCookies(target, "target-login-fixture");
        jdbc.update("INSERT INTO boss_blacklist(account_id,type,value) VALUES(1,'company','模拟公司'),(1,'job','模拟岗位'),(1,'recruiter','模拟 HR'),(?,'company','目标旧黑名单')", target);
        jdbc.update("INSERT INTO boss_data(account_id,job_name,delivery_status) VALUES(1,'来源记录','已投递'),(?,'目标记录','未投递')", target);
        Path sourceImage = saveSyncImage(images, 1, "jpg", 0xFF0020);
        Path previousTargetImage = saveSyncImage(images, target, "png", 0x008020);
        try (var scope = BossAccountContext.use(target)) {
            reviews.begin("history", com.getjobs.worker.boss.review.BossReviewPolicy.defaults());
        }

        var registry = new org.springframework.web.servlet.config.annotation.InterceptorRegistry() {
            public org.springframework.web.servlet.HandlerInterceptor[] interceptors() {
                return getInterceptors().stream().map(org.springframework.web.servlet.HandlerInterceptor.class::cast)
                        .toArray(org.springframework.web.servlet.HandlerInterceptor[]::new);
            }
        };
        new com.getjobs.application.config.BossAccountWebConfig(accounts, locks).addInterceptors(registry);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.getjobs.application.controller.BossConfigSyncController(syncService))
                .addInterceptors(registry.interceptors()).setControllerAdvice(new com.getjobs.application.controller.BossApiErrors()).build();
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/boss/accounts/" + target + "/sync-config")
                .contentType("application/json").content("{\"sourceAccountId\":1}");
        locks.reserve(List.of(target));
        try { assertEquals(409, mvc.perform(request).andReturn().getResponse().getStatus()); }
        finally { locks.release(); }
        assertEquals("目标旧招呼", jdbc.queryForObject("SELECT say_hi FROM boss_config WHERE account_id=?", String.class, target));
        locks.reserve(List.of(1L));
        try { assertEquals(200, mvc.perform(request).andReturn().getResponse().getStatus()); }
        finally { locks.release(); }
        var sourceConfig = jdbc.queryForMap("SELECT * FROM boss_config WHERE account_id=1");
        var targetConfig = jdbc.queryForMap("SELECT * FROM boss_config WHERE account_id=?", target);
        for (String column : List.of("id", "account_id")) {
            sourceConfig.remove(column);
            targetConfig.remove(column);
        }
        assertEquals(sourceConfig, targetConfig);
        var sourceBlacklist = jdbc.queryForList("SELECT type,value FROM boss_blacklist WHERE account_id=1 ORDER BY id");
        assertEquals(sourceBlacklist, jdbc.queryForList("SELECT type,value FROM boss_blacklist WHERE account_id=? ORDER BY id", target));
        assertEquals(accounts.profile(1), accounts.profile(target));
        assertEquals("目标账号", accounts.require(target).name());
        assertEquals("target-login-fixture", accounts.cookies(target));
        assertEquals("目标记录", jdbc.queryForObject("SELECT job_name FROM boss_data WHERE account_id=?", String.class, target));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM boss_review_runs WHERE account_id=?", Integer.class, target));
        try (var scope = BossAccountContext.use(target)) {
            assertEquals(new com.getjobs.worker.boss.BossCityPolicy(true, 20, 2), citySettings.policy());
            assertEquals(5, hrSettings.policy().maxDays());
            assertEquals(List.of("模拟开发"), reviews.policy().targetRoles());
            assertEquals(new com.getjobs.worker.boss.review.BossReviewEconomy(false, 14, 5000), reviews.economy());
            assertArrayEquals(Files.readAllBytes(sourceImage), Files.readAllBytes(images.getCurrentImagePath().orElseThrow()));
            assertFalse(Files.exists(previousTargetImage));
        }

        jdbc.update("DELETE FROM boss_review_economy WHERE account_id=1");
        Files.delete(sourceImage);
        assertEquals(200, mvc.perform(request).andReturn().getResponse().getStatus());
        try (var scope = BossAccountContext.use(target)) {
            assertEquals(com.getjobs.worker.boss.review.BossReviewEconomy.defaults(), reviews.economy());
            assertTrue(images.getCurrentImagePath().isEmpty());
        }
    }

    @Test void failedConfigSyncRollsBackBothDatabaseAndTheTargetResume() throws Exception {
        long target = accounts.create("保留配置的账号", null).id();
        var images = new BossResumeImageService(directory.resolve("rollback-resumes"));
        var syncService = configSyncService(images);
        var originalConfig = jdbc.queryForMap("SELECT * FROM boss_config WHERE account_id=?", target);
        jdbc.update("INSERT INTO boss_blacklist(account_id,type,value) VALUES(1,'job','来源黑名单'),(?,'job','目标黑名单')", target);
        saveSyncImage(images, 1, "jpg", 0xFF1111);
        Path originalImage = saveSyncImage(images, target, "png", 0x1010FF);
        byte[] originalBytes = Files.readAllBytes(originalImage);
        jdbc.execute("CREATE TRIGGER fail_sync BEFORE UPDATE OF introduce ON boss_accounts WHEN OLD.id=" + target
                + " BEGIN SELECT RAISE(ABORT,'synthetic sync failure'); END");

        var failure = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> syncService.sync(1, target));
        assertEquals(500, failure.getStatusCode().value());
        assertEquals(originalConfig, jdbc.queryForMap("SELECT * FROM boss_config WHERE account_id=?", target));
        assertEquals(List.of("目标黑名单"), jdbc.queryForList("SELECT value FROM boss_blacklist WHERE account_id=?", String.class, target));
        try (var scope = BossAccountContext.use(target)) {
            assertEquals(originalImage, images.getCurrentImagePath().orElseThrow());
            assertArrayEquals(originalBytes, Files.readAllBytes(originalImage));
            assertFalse(Files.exists(originalImage.resolveSibling("resume.jpg")));
        }
        locks.reserve(List.of(target));
        locks.release();
    }

    private BossConfigSyncService configSyncService(BossResumeImageService images) {
        new BossReviewStore(jdbc, new com.fasterxml.jackson.databind.ObjectMapper()).migrate();
        return new BossConfigSyncService(jdbc, accounts, locks, images,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private static Path saveSyncImage(BossResumeImageService images, long accountId, String format, int color) throws Exception {
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        var contents = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, format, contents));
        try (var scope = BossAccountContext.use(accountId)) {
            return images.save(new org.springframework.mock.web.MockMultipartFile(
                    "file", "resume." + format, "image/" + format, contents.toByteArray())).path();
        }
    }

    @Test void httpRequestsScopeUpdatesAndStreamsAndRejectCrossAccountAndLockedWrites() throws Exception {
        long second=accounts.create("第二账号",null).id();
        var registry=new org.springframework.web.servlet.config.annotation.InterceptorRegistry() {
            public org.springframework.web.servlet.HandlerInterceptor[] interceptors() {
                return getInterceptors().stream().map(org.springframework.web.servlet.HandlerInterceptor.class::cast)
                        .toArray(org.springframework.web.servlet.HandlerInterceptor[]::new);
            }
        };
        new com.getjobs.application.config.BossAccountWebConfig(accounts,locks).addInterceptors(registry);
        var jobs=org.mockito.Mockito.mock(com.getjobs.worker.service.BossJobService.class);
        var browsers=org.mockito.Mockito.mock(com.getjobs.worker.manager.BossBrowserSessions.class);
        org.mockito.Mockito.when(jobs.getStatus(second)).thenReturn(java.util.Map.of("accountId",second));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.getjobs.application.controller.BossConfigController(boss,new BossResumeImageService(),citySettings,hrSettings),
                new com.getjobs.application.controller.BossController(jobs,browsers),
                new com.getjobs.application.controller.BossAccountController(accounts,browsers,jobs))
                .addInterceptors(registry.interceptors()).setControllerAdvice(new com.getjobs.application.controller.BossApiErrors()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/boss/config?accountId="+second)
                .contentType("application/json").content("{\"id\":1,\"accountId\":1,\"sayHi\":\"第二账号消息\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals(1,BossAccountContext.id()); assertEquals("原有招呼语",boss.getFirstConfig().getSayHi());
        try (var scope=BossAccountContext.use(second)) { assertEquals("第二账号消息",boss.getFirstConfig().getSayHi()); }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/boss/status?accountId=999"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/boss/status?accountId=wrong"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/boss/accounts/"+second+"/profile?accountId=1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        locks.reserve(java.util.List.of(second));
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/boss/config?accountId="+second)
                    .contentType("application/json").content("{\"sayHi\":\"不允许修改\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        } finally { locks.release(); }
        var stream=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/boss/stream?accountId="+second))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        try {
            assertEquals(1,BossAccountContext.id());
            assertTrue(stream.getResponse().getContentAsString().contains("\"accountId\":"+second));
        } finally { stream.getRequest().getAsyncContext().complete(); }
    }
}
