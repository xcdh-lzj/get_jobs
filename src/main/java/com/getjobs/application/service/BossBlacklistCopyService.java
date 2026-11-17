package com.getjobs.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

/** 只合并黑名单；目标账号由请求账号范围确定，整个事务提交前禁止启动该账号任务。 */
@Service
@RequiredArgsConstructor
public class BossBlacklistCopyService {
    private static final List<String> TYPES=List.of("company","job","recruiter");
    private final JdbcTemplate jdbc;
    private final BossAccountService accounts;
    private final BossAccountLocks locks;
    private final PlatformTransactionManager transactionManager;

    public record Entry(long id,long accountId,String type,String value) {}
    private record Key(String type,String value) {}
    public record Result(long sourceAccountId,String sourceAccountName,long targetAccountId,String targetAccountName,
                         List<String> types,int sourceCount,int copiedCount,int skippedCount,List<Entry> blacklist) {}

    public Result copy(long sourceAccountId,List<String> requestedTypes) {
        long targetAccountId=BossAccountContext.id();
        if(sourceAccountId<1)throw badRequest("请选择有效的来源账号");
        if(sourceAccountId==targetAccountId)throw badRequest("来源账号与当前账号相同，无需复制");
        List<String> types=requestedTypes==null?TYPES:requestedTypes;
        if(types.isEmpty() || types.size()>3 || types.stream().anyMatch(t -> t==null || !TYPES.contains(t))
                || types.stream().distinct().count()!=types.size())
            throw badRequest("请选择公司、岗位、HR 中至少一类黑名单，不能重复选择");
        var selected=Set.copyOf(types);
        locks.beginEdit(targetAccountId);
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                var source=accounts.require(sourceAccountId);var target=accounts.require(targetAccountId);
                var candidates=new LinkedHashSet<Key>();
                for(var entry:entries(sourceAccountId)) {
                    var key=key(entry);
                    if(key!=null && selected.contains(key.type()))candidates.add(key);
                }
                var existing=new HashSet<Key>();
                for(var entry:entries(targetAccountId)){var key=key(entry);if(key!=null)existing.add(key);}
                int copied=0;
                String now=LocalDateTime.now().toString();
                for(var entry:candidates)if(existing.add(entry)) {
                    jdbc.update("INSERT INTO boss_blacklist(account_id,type,value,created_at,updated_at) VALUES(?,?,?,?,?)",
                            targetAccountId,entry.type(),entry.value(),now,now);
                    copied++;
                }
                return new Result(source.id(),source.name(),target.id(),target.name(),TYPES.stream().filter(selected::contains).toList(),
                        candidates.size(),copied,candidates.size()-copied,entries(targetAccountId));
            });
        } finally { locks.endEdit(); }
    }

    private List<Entry> entries(long accountId) {
        return jdbc.query("SELECT id,account_id,type,value FROM boss_blacklist WHERE account_id=? ORDER BY id",
                (row,n)->new Entry(row.getLong("id"),row.getLong("account_id"),row.getString("type"),row.getString("value")),accountId);
    }
    private static Key key(Entry entry) {
        String type="boss".equals(entry.type())?"company":entry.type();
        if(type==null || !TYPES.contains(type) || entry.value()==null || entry.value().isBlank())return null;
        return new Key(type,entry.value().strip());
    }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
