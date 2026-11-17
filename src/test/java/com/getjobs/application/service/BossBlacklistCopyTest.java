package com.getjobs.application.service;

import com.getjobs.application.config.BossAccountWebConfig;
import com.getjobs.application.controller.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BossBlacklistCopyTest {
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private BossAccountService accounts;
    private BossAccountLocks locks;
    private BossBlacklistCopyService copies;

    @BeforeEach void setup() {
        var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("blacklist.db"));
        jdbc=new JdbcTemplate(source);locks=new BossAccountLocks();accounts=mock(BossAccountService.class);
        jdbc.execute("CREATE TABLE boss_accounts(id INTEGER PRIMARY KEY,name TEXT,introduce TEXT,cookie_value TEXT)");
        jdbc.execute("INSERT INTO boss_accounts VALUES(1,'账号1','来源介绍','source-cookie'),(2,'账号2','目标介绍','target-cookie'),(3,'账号3','第三账号','third-cookie')");
        jdbc.execute("CREATE TABLE boss_config(account_id INTEGER PRIMARY KEY,keywords TEXT,city_code TEXT)");
        jdbc.execute("INSERT INTO boss_config VALUES(1,'Agent','广州'),(2,'大模型应用','深圳'),(3,'产品','上海')");
        jdbc.execute("CREATE TABLE boss_blacklist(id INTEGER PRIMARY KEY AUTOINCREMENT,account_id INTEGER NOT NULL,type TEXT,value TEXT,created_at TEXT,updated_at TEXT)");
        add(1,"company"," 公司A ");add(1,"boss","公司A");add(1,"job","销售经理");add(1,"job","产品经理");add(1,"recruiter","猎头");add(1,"company","公司B");
        add(1,"company"," ");add(1,"job",null);add(1,"other","不支持的分类");
        add(2,"boss","公司A");add(2,"job","账号2原有岗位");add(3,"company","账号3保留");
        when(accounts.require(anyLong())).thenAnswer(call -> {
            long id=call.getArgument(0);var rows=jdbc.query("SELECT id,name FROM boss_accounts WHERE id=?",(r,n)->new BossAccountService.Account(r.getLong(1),r.getString(2),r.getLong(1)==1),id);
            if(rows.isEmpty())throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Boss 账号不存在");
            return rows.getFirst();
        });
        copies=new BossBlacklistCopyService(jdbc,accounts,locks,new DataSourceTransactionManager(source));
    }
    @AfterEach void clear(){BossAccountContext.clear();locks.release();}
    private void add(long id,String type,String value){jdbc.update("INSERT INTO boss_blacklist(account_id,type,value,created_at,updated_at) VALUES(?,?,?,'old-created','old-updated')",id,type,value);}
    private List<Map<String,Object>> entries(long id){return jdbc.queryForList("SELECT * FROM boss_blacklist WHERE account_id=? ORDER BY id",id);}

    @Test void mergesDeduplicatesAndRemainsIndependentWithoutChangingOtherAccountData() {
        var original=entries(1);var third=entries(3);var existing=entries(2);
        var configs=jdbc.queryForList("SELECT * FROM boss_config ORDER BY account_id");var profiles=jdbc.queryForList("SELECT * FROM boss_accounts ORDER BY id");
        try(var scope=BossAccountContext.use(2)) {
            var result=copies.copy(1,null);
            assertEquals(1,result.sourceAccountId());assertEquals(2,result.targetAccountId());assertEquals("账号2",result.targetAccountName());
            assertEquals(5,result.sourceCount());assertEquals(4,result.copiedCount());assertEquals(1,result.skippedCount());assertEquals(6,result.blacklist().size());
            var repeat=copies.copy(1,List.of("company","job","recruiter"));assertEquals(0,repeat.copiedCount());assertEquals(5,repeat.skippedCount());
            assertEquals(existing,entries(2).subList(0,existing.size()));
            jdbc.update("DELETE FROM boss_blacklist WHERE account_id=2 AND value='销售经理'");
        }
        assertEquals(original,entries(1));assertEquals(third,entries(3));
        assertEquals(configs,jdbc.queryForList("SELECT * FROM boss_config ORDER BY account_id"));
        assertEquals(profiles,jdbc.queryForList("SELECT * FROM boss_accounts ORDER BY id"));
    }

    @Test void selectedTypesOnlyAndSameKeywordInDifferentCategoriesIsNotLost() {
        add(1,"job","公司A");
        try(var scope=BossAccountContext.use(2)) {
            var result=copies.copy(1,List.of("job"));assertEquals(3,result.copiedCount());assertEquals(List.of("job"),result.types());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM boss_blacklist WHERE account_id=2 AND type='job' AND value='公司A'",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM boss_blacklist WHERE account_id=2 AND type='recruiter'",Integer.class));
            var none=copies.copy(3,List.of("job"));assertEquals(0,none.sourceCount());assertEquals(0,none.copiedCount());
        }
    }

    @Test void invalidSelectionsAndAccountIdsDoNotChangeData() {
        var before=entries(2);
        try(var scope=BossAccountContext.use(2)) {
            assertEquals(400,assertThrows(ResponseStatusException.class,()->copies.copy(2,null)).getStatusCode().value());
            assertEquals(400,assertThrows(ResponseStatusException.class,()->copies.copy(0,null)).getStatusCode().value());
            assertEquals(404,assertThrows(ResponseStatusException.class,()->copies.copy(99,null)).getStatusCode().value());
            for(var types:List.of(List.<String>of(),List.of("other"),List.of("company","company"),Arrays.asList("job",null)))
                assertEquals(400,assertThrows(ResponseStatusException.class,()->copies.copy(1,types)).getStatusCode().value());
        }
        assertEquals(before,entries(2));assertDoesNotThrow(()->locks.reserve(List.of(2L)));
    }

    @Test void reservedTargetIsRejectedButReadingFromReservedSourceIsAllowed() {
        var before=entries(2);locks.reserve(List.of(2L));
        try(var scope=BossAccountContext.use(2)) {assertEquals(409,assertThrows(ResponseStatusException.class,()->copies.copy(1,null)).getStatusCode().value());}
        assertEquals(before,entries(2));locks.release();locks.reserve(List.of(1L));
        try(var scope=BossAccountContext.use(2)){assertEquals(2,copies.copy(1,List.of("job")).copiedCount());}
    }

    @Test void failedInsertRollsBackAllCopiedRowsAndReleasesTaskReservationGuard() {
        var before=entries(2);var source=entries(1);
        jdbc.execute("CREATE TRIGGER copy_failure BEFORE INSERT ON boss_blacklist WHEN NEW.account_id=2 AND NEW.value='产品经理' BEGIN SELECT RAISE(ABORT,'simulated insert failure'); END");
        try(var scope=BossAccountContext.use(2)){assertThrows(org.springframework.dao.DataAccessException.class,()->copies.copy(1,null));}
        assertEquals(before,entries(2));assertEquals(source,entries(1));assertDoesNotThrow(()->locks.reserve(List.of(2L)));
    }

    @Test void accountTaskCannotReserveTargetUntilCopyTransactionHasCommitted() throws Exception {
        var source=(javax.sql.DataSource)jdbc.getDataSource();
        var actual=new DataSourceTransactionManager(source);
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        var committing=new java.util.concurrent.CountDownLatch(1);var finishCommit=new java.util.concurrent.CountDownLatch(1);
        when(manager.getTransaction(any())).thenAnswer(call -> actual.getTransaction(call.getArgument(0)));
        doAnswer(call -> {committing.countDown();assertTrue(finishCommit.await(5,java.util.concurrent.TimeUnit.SECONDS));actual.commit(call.getArgument(0));return null;}).when(manager).commit(any());
        doAnswer(call -> {actual.rollback(call.getArgument(0));return null;}).when(manager).rollback(any());
        var guarded=new BossBlacklistCopyService(jdbc,accounts,locks,manager);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var copy=pool.submit(() -> {try(var scope=BossAccountContext.use(2)){return guarded.copy(1,List.of("job"));}});
            try {
                assertTrue(committing.await(5,java.util.concurrent.TimeUnit.SECONDS));
                var reserved=new java.util.concurrent.CountDownLatch(1);
                var task=pool.submit(() -> {locks.reserve(List.of(2L));reserved.countDown();});
                assertFalse(reserved.await(100,java.util.concurrent.TimeUnit.MILLISECONDS));
                finishCommit.countDown();assertEquals(2,copy.get(5,java.util.concurrent.TimeUnit.SECONDS).copiedCount());task.get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(locks.busy(2));
            } finally {finishCommit.countDown();}
        }
    }

    @Test void httpTargetComesFromAccountScopeAndLockedOrInvalidRequestsReturnClearErrors() throws Exception {
        class Registry extends org.springframework.web.servlet.config.annotation.InterceptorRegistry {
            org.springframework.web.servlet.HandlerInterceptor[] all(){return getInterceptors().stream().map(org.springframework.web.servlet.HandlerInterceptor.class::cast).toArray(org.springframework.web.servlet.HandlerInterceptor[]::new);}
        }
        var registry=new Registry();new BossAccountWebConfig(accounts,locks).addInterceptors(registry);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new BossBlacklistCopyController(copies))
                .addInterceptors(registry.all()).setControllerAdvice(new BossApiErrors()).build();
        var third=entries(3);
        mvc.perform(post("/api/boss/config/blacklist/copy?accountId=2").contentType("application/json")
                .content("{\"sourceAccountId\":1,\"targetAccountId\":3,\"types\":[\"company\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.targetAccountId").value(2)).andExpect(jsonPath("$.copiedCount").value(1));
        assertEquals(third,entries(3));assertEquals(1,BossAccountContext.id());
        mvc.perform(post("/api/boss/config/blacklist/copy?accountId=2").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("请选择来源账号"));
        mvc.perform(post("/api/boss/config/blacklist/copy?accountId=2").contentType("application/json").content("{\"sourceAccountId\":99}"))
                .andExpect(status().isNotFound());
        locks.reserve(List.of(2L));
        mvc.perform(post("/api/boss/config/blacklist/copy?accountId=2").contentType("application/json").content("{\"sourceAccountId\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").exists());
        assertEquals(1,BossAccountContext.id());
    }
}
