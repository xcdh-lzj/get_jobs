package com.getjobs.application.init;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Only fresh databases receive the bundled page settings; upgrades retain user data. */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class DefaultPageConfigInitializer implements CommandLineRunner {
    private static final List<String> TABLES = List.of(
            "boss_config", "liepin_config", "job51_config", "zhilian_config", "ai", "boss_blacklist");
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    @Value("${getjobs.defaults-file:}")
    private String defaultsFile = "";

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS default_page_config_seed (id INTEGER PRIMARY KEY)");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM default_page_config_seed", Integer.class) > 0) {
            return;
        }
        boolean fresh = true;
        for (String table : TABLES) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class) > 0) {
                fresh = false;
            }
        }
        if (fresh) {
            Map<String, List<Map<String, Object>>> defaults;
            Resource resource = defaultsFile.isBlank()
                    ? new ClassPathResource("defaults/page-config.json")
                    : new FileSystemResource(defaultsFile);
            if (!resource.isReadable()) {
                throw new IllegalStateException("默认配置文件不可读，请检查 getjobs.defaults-file");
            }
            try (var input = resource.getInputStream()) {
                defaults = objectMapper.readValue(input, new TypeReference<>() {});
            }
            if (!defaults.keySet().equals(Set.copyOf(TABLES))) {
                throw new IllegalStateException("默认页面配置的表范围不正确");
            }
            for (String table : TABLES) {
                var columns = Set.copyOf(jdbc.query("PRAGMA table_info(" + table + ")",
                        (rs, index) -> rs.getString("name")));
                for (Map<String, Object> row : defaults.get(table)) {
                    if (row.isEmpty() || !columns.containsAll(row.keySet())
                            || row.keySet().stream().anyMatch(k -> !k.matches("[a-z_]+"))) {
                        throw new IllegalStateException("默认页面配置字段不正确: " + table);
                    }
                    var names = List.copyOf(row.keySet());
                    String placeholders = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
                    jdbc.update("INSERT INTO " + table + " (" + String.join(",", names)
                            + ") VALUES (" + placeholders + ")", names.stream().map(row::get).toArray());
                }
            }
            log.info("已加载{}页面配置（四个平台、AI内容、Boss黑名单）", defaultsFile.isBlank() ? "通用默认" : "本机初始化");
        } else {
            log.info("检测到已有页面配置，保留用户设置，不加载项目默认值");
        }
        // Remember even skipped installations, so a deliberately cleared blacklist stays empty.
        jdbc.update("INSERT INTO default_page_config_seed (id) VALUES (1)");
    }
}
