package com.getjobs.application.service;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.getjobs.application.entity.*;
import com.getjobs.application.mapper.*;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import java.nio.file.*;
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
        boss.updateDeliveryStatus("same-job","same-recruiter","已投递");
        assertEquals(1,boss.getBossStats().kpi.delivered);
        try (var scope=BossAccountContext.use(second)) {
            assertEquals(0,boss.getBossStats().kpi.delivered);
            assertEquals(1,boss.getBossStats().kpi.pending);
            var rows=boss.listBossJobs(null,null,null,null,null,null,null,1,20,false);
            assertEquals(1,rows.total); assertEquals("未投递",rows.items.getFirst().getDeliveryStatus());
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
        var partial=new com.getjobs.worker.boss.review.BossReviewJob("same-job","更新后的岗位名","","","","","","","","","https://www.zhipin.com/job_detail/same-job.html","");
        boss.recordPrecisionJob(partial,"已过滤");
        var saved=session.getMapper(BossJobDataMapper.class).selectById(existing.getId());
        assertEquals("更新后的岗位名",saved.getJobName());assertEquals("原公司",saved.getCompanyName());
        assertEquals("完整原始JD",saved.getJobDescription());assertEquals("技术负责人",saved.getHrPosition());
        assertEquals("20-30K",saved.getSalary());assertEquals("已投递",saved.getDeliveryStatus());
        long second=accounts.create("第二账号",null).id();
        try(var scope=BossAccountContext.use(second)) {boss.recordPrecisionJob(partial,"未投递");}
        assertEquals("已投递",session.getMapper(BossJobDataMapper.class).selectById(existing.getId()).getDeliveryStatus());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM boss_data",Integer.class));
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
