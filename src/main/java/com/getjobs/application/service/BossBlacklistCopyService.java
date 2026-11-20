package com.getjobs.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

/** 合并其他账号或备份文件的黑名单，写入期间禁止启动目标账号任务。 */
@Service
@RequiredArgsConstructor
public class BossBlacklistCopyService {
    private static final List<String> TYPES=List.of("company","job","recruiter");
    private static final int FILE_VERSION = 1;
    private final JdbcTemplate jdbc;
    private final BossAccountService accounts;
    private final BossAccountLocks locks;
    private final PlatformTransactionManager transactionManager;

    public record Entry(long id,long accountId,String type,String value) {}
    public record Keyword(String type, String value) {}
    public record FileData(int version, List<Keyword> entries) {}
    public record ImportResult(int importedCount, int skippedCount, List<Entry> blacklist) {}
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
                var candidates = keywords(sourceAccountId);
                candidates.removeIf(keyword -> !selected.contains(keyword.type()));
                int copied = merge(targetAccountId, candidates);
                return new Result(source.id(),source.name(),target.id(),target.name(),TYPES.stream().filter(selected::contains).toList(),
                        candidates.size(),copied,candidates.size()-copied,entries(targetAccountId));
            });
        } finally { locks.endEdit(); }
    }

    public FileData exportBlacklist() {
        return new FileData(FILE_VERSION, List.copyOf(keywords(BossAccountContext.id())));
    }

    public ImportResult importBlacklist(JsonNode document) {
        if (document == null || !document.path("version").isInt()
                || document.path("version").intValue() != FILE_VERSION || !document.path("entries").isArray()) {
            throw badRequest("黑名单文件格式或版本无效，请选择黑名单管理导出的 JSON 文件");
        }
        var items = document.get("entries");
        var candidates = new LinkedHashSet<Keyword>();
        int rowNumber = 0;
        for (var item : items) {
            rowNumber++;
            var keyword = keyword(item.path("type").textValue(), item.path("value").textValue());
            if (keyword == null) {
                throw badRequest("第 " + rowNumber + " 条黑名单无效，请检查分类和关键词");
            }
            candidates.add(keyword);
        }

        long accountId = BossAccountContext.id();
        locks.beginEdit(accountId);
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                accounts.require(accountId);
                int imported = merge(accountId, candidates);
                return new ImportResult(imported, items.size() - imported, entries(accountId));
            });
        } finally {
            locks.endEdit();
        }
    }

    private int merge(long accountId, Set<Keyword> candidates) {
        var existing = keywords(accountId);
        String now = LocalDateTime.now().toString();
        int added = 0;
        for (var keyword : candidates) {
            if (existing.add(keyword)) {
                added += jdbc.update("INSERT INTO boss_blacklist(account_id,type,value,created_at,updated_at) VALUES(?,?,?,?,?)",
                        accountId, keyword.type(), keyword.value(), now, now);
            }
        }
        return added;
    }

    private Set<Keyword> keywords(long accountId) {
        var keywords = new LinkedHashSet<Keyword>();
        for (var entry : entries(accountId)) {
            var keyword = keyword(entry.type(), entry.value());
            if (keyword != null) keywords.add(keyword);
        }
        return keywords;
    }

    private List<Entry> entries(long accountId) {
        return jdbc.query("SELECT id,account_id,type,value FROM boss_blacklist WHERE account_id=? ORDER BY id",
                (row,n)->new Entry(row.getLong("id"),row.getLong("account_id"),row.getString("type"),row.getString("value")),accountId);
    }
    private static Keyword keyword(String type, String value) {
        String category = "boss".equals(type) ? "company" : type;
        if (category == null || !TYPES.contains(category) || value == null || value.isBlank()) return null;
        return new Keyword(category, value.strip());
    }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
