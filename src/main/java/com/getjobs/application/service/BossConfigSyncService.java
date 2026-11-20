package com.getjobs.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/** 将来源账号已保存的配置作为一个整体复制到目标账号。 */
@Service
@RequiredArgsConstructor
public class BossConfigSyncService {
    private static final String CONFIG_COLUMNS = """
            debugger,wait_time,keywords,city_code,industry,job_type,experience,degree,salary,salary_filter_policy,
            scale,stage,say_hi,expected_salary_min,expected_salary_max,enable_ai,send_img_resume,allow_repeat_chat,
            repeat_chat_messages,follow_up_settings,filter_dead_hr,dead_status,created_at,updated_at
            """;

    private final JdbcTemplate jdbc;
    private final BossAccountService accounts;
    private final BossAccountLocks locks;
    private final BossResumeImageService resumeImages;
    private final PlatformTransactionManager transactionManager;

    public record Result(long sourceAccountId, long targetAccountId, String message) {}

    public Result sync(long sourceId, long targetId) {
        if (sourceId < 1 || sourceId == targetId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择不同的来源账号");
        }
        locks.beginConfigSync(targetId);
        try {
            var source = accounts.require(sourceId);
            var target = accounts.require(targetId);
            return resumeImages.withSyncedImage(sourceId, targetId, () ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        replaceRows("boss_config", CONFIG_COLUMNS, sourceId, targetId, " ORDER BY id");
                        replaceRows("boss_blacklist", "type,value,created_at,updated_at", sourceId, targetId, " ORDER BY id");
                        replaceRows("boss_city_policy", "policy_json,updated_at", sourceId, targetId, "");
                        replaceRows("boss_hr_policy", "policy_json,updated_at", sourceId, targetId, "");
                        replaceRows("boss_review_policy", "policy_json,updated_at", sourceId, targetId, "");
                        replaceRows("boss_review_economy", "settings_json", sourceId, targetId, "");
                        jdbc.update("""
                                UPDATE boss_accounts SET (introduce,prompt,profile_seeded)=
                                    (SELECT introduce,prompt,1 FROM boss_accounts WHERE id=?)
                                WHERE id=?
                                """, sourceId, targetId);
                        return new Result(sourceId, targetId,
                                "已将「" + source.name() + "」的全部已保存配置同步到「" + target.name() + "」。");
                    }));
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "图片简历同步失败，请检查本地文件后重试", error);
        } catch (DataAccessException | TransactionException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "账号配置同步失败，请稍后重试", error);
        } finally {
            locks.endConfigSync();
        }
    }

    private void replaceRows(String table, String columns, long sourceId, long targetId, String order) {
        jdbc.update("DELETE FROM " + table + " WHERE account_id=?", targetId);
        jdbc.update("INSERT INTO " + table + "(account_id," + columns + ") SELECT ?," + columns
                + " FROM " + table + " WHERE account_id=?" + order, targetId, sourceId);
    }
}
