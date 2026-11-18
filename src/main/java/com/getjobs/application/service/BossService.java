package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.getjobs.application.entity.BlacklistEntity;
import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.application.entity.BossIndustryEntity;
import com.getjobs.application.entity.BossOptionEntity;
import com.getjobs.application.mapper.BlacklistMapper;
import com.getjobs.application.mapper.BossJobDataMapper;
import com.getjobs.application.entity.BossJobDataEntity;
import com.getjobs.application.mapper.BossConfigMapper;
import com.getjobs.application.mapper.BossIndustryMapper;
import com.getjobs.application.mapper.BossOptionMapper;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.boss.review.BossCandidateCollector;
import com.getjobs.worker.boss.review.BossReviewJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Boss数据服务
 * 统一管理所有Boss相关的数据访问和配置加载
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BossService {

    private static final String NATIONWIDE_CITY_CODE = "100010000";

    private final BossOptionMapper bossOptionMapper;
    private final BossIndustryMapper bossIndustryMapper;
    private final BossConfigMapper bossConfigMapper;
    private final BlacklistMapper blacklistMapper;
    private final BossJobDataMapper bossJobDataMapper;
    private final javax.sql.DataSource dataSource;

    /** 为旧数据库补充开关，不覆盖已有配置；新空库也会在首次启动时补齐。 */
    @jakarta.annotation.PostConstruct
    public void ensureRepeatChatColumn() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            boolean tableExists = false;
            Set<String> names = new java.util.HashSet<>();
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info('boss_config')")) {
                while (columns.next()) {
                    tableExists = true;
                    names.add(columns.getString("name"));
                }
            }
            if (tableExists && !names.contains("allow_repeat_chat")) {
                statement.executeUpdate("ALTER TABLE boss_config ADD COLUMN allow_repeat_chat INTEGER NOT NULL DEFAULT 0");
                log.info("已添加Boss重复沟通开关，默认关闭");
            }
            if (tableExists && !names.contains("repeat_chat_messages")) {
                statement.executeUpdate("ALTER TABLE boss_config ADD COLUMN repeat_chat_messages TEXT");
            }
            if (tableExists && !names.contains("salary_filter_policy")) {
                String url=connection.getMetaData().getURL();
                if(url.startsWith("jdbc:sqlite:") && !url.contains(":memory:") && !url.substring(12).startsWith("file:")) {
                    var db=java.nio.file.Path.of(url.substring(12)).toAbsolutePath();
                    var backup=db.resolveSibling(db.getFileName()+".before-boss-salary-policy.bak");
                    if(!java.nio.file.Files.exists(backup))try(var query=connection.prepareStatement("VACUUM INTO ?")){query.setString(1,backup.toString());query.execute();}
                }
                statement.executeUpdate("ALTER TABLE boss_config ADD COLUMN salary_filter_policy TEXT");
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("无法初始化Boss重复沟通配置", e);
        }
    }

    // ==================== Option相关方法 ====================

    /**
     * 根据类型获取选项列表
     */
    public List<BossOptionEntity> getOptionsByType(String type) {
        // 确保数据库存在『不限』选项（code=0），并置顶显示
        // city 与 industry 都需要此默认项
        QueryWrapper<BossOptionEntity> checkWrapper = new QueryWrapper<>();
        checkWrapper.eq("type", type);
        checkWrapper.eq("code", com.getjobs.worker.utils.Constant.UNLIMITED_CODE);
        Long count = bossOptionMapper.selectCount(checkWrapper);
        if (count == null || count == 0) {
            BossOptionEntity unlimited = new BossOptionEntity();
            unlimited.setType(type);
            unlimited.setName("不限");
            unlimited.setCode(com.getjobs.worker.utils.Constant.UNLIMITED_CODE);
            // 置顶显示
            unlimited.setSortOrder(0);
            unlimited.setCreatedAt(java.time.LocalDateTime.now());
            unlimited.setUpdatedAt(java.time.LocalDateTime.now());
            bossOptionMapper.insert(unlimited);
        }

        // Boss 的“全国”是一个实际城市筛选项，不能复用“不限(0)”。
        // code=0 会被构建 URL 时忽略，随后 Boss 会按当前定位城市（常见为北京）搜索。
        if ("city".equals(type)) {
            QueryWrapper<BossOptionEntity> nationwideWrapper = new QueryWrapper<>();
            nationwideWrapper.eq("type", "city");
            nationwideWrapper.eq("code", NATIONWIDE_CITY_CODE);
            Long nationwideCount = bossOptionMapper.selectCount(nationwideWrapper);
            if (nationwideCount == null || nationwideCount == 0) {
                BossOptionEntity nationwide = new BossOptionEntity();
                nationwide.setType("city");
                nationwide.setName("全国");
                nationwide.setCode(NATIONWIDE_CITY_CODE);
                nationwide.setSortOrder(1);
                nationwide.setCreatedAt(LocalDateTime.now());
                nationwide.setUpdatedAt(LocalDateTime.now());
                bossOptionMapper.insert(nationwide);
            }
        }

        // 排序：city/industry 按 sort_order 优先，其次 id；其他类型维持原有 id 升序
        QueryWrapper<BossOptionEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("type", type);
        if ("city".equals(type) || "industry".equals(type)) {
            // SQLite 下可用：ORDER BY sort_order IS NULL, sort_order ASC, id ASC
            wrapper.last("ORDER BY sort_order IS NULL, sort_order ASC, id ASC");
        } else {
            wrapper.orderByAsc("id");
        }
        return bossOptionMapper.selectList(wrapper);
    }

    /**
     * 获取所有选项
     */
    public List<BossOptionEntity> getAllOptions() {
        return bossOptionMapper.selectList(null);
    }

    /**
     * 根据类型和代码获取选项
     */
    public BossOptionEntity getOptionByTypeAndCode(String type, String code) {
        QueryWrapper<BossOptionEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("type", type);
        wrapper.eq("code", code);
        return bossOptionMapper.selectOne(wrapper);
    }

    /**
     * 根据类型和名称获取代码
     * 如果找不到，返回默认值 "0"
     */
    public String getCodeByTypeAndName(String type, String name) {
        QueryWrapper<BossOptionEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("type", type);
        wrapper.eq("name", name);
        BossOptionEntity entity = bossOptionMapper.selectOne(wrapper);
        return entity != null ? entity.getCode() : "0";
    }

    // ==================== City相关方法 ====================

    /**
     * 根据城市名称获取代码（从 boss_option 表中按 type=city 查询）
     * 如果找不到，返回默认值 "0"
     */
    public String getCityCodeByName(String name) {
        QueryWrapper<BossOptionEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("type", "city");
        wrapper.eq("name", name);
        BossOptionEntity entity = bossOptionMapper.selectOne(wrapper);
        return entity != null ? entity.getCode() : "0";
    }

    // ==================== Industry相关方法 ====================

    /**
     * 获取所有行业
     */
    public List<BossIndustryEntity> getAllIndustries() {
        return bossIndustryMapper.selectList(null);
    }

    /**
     * 根据代码获取行业
     */
    public BossIndustryEntity getIndustryByCode(Integer code) {
        return bossIndustryMapper.selectById(code);
    }

    /**
     * 根据行业名称获取代码
     * 如果找不到，返回默认值 "0"
     */
    public String getIndustryCodeByName(String name) {
        QueryWrapper<BossIndustryEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("name", name);
        BossIndustryEntity entity = bossIndustryMapper.selectOne(wrapper);
        return entity != null ? String.valueOf(entity.getCode()) : "0";
    }

    // ==================== BossConfig相关方法 ====================

    /**
     * 获取所有配置
     */
    public List<BossConfigEntity> getAllConfigs() {
        return bossConfigMapper.selectList(new QueryWrapper<BossConfigEntity>().eq("account_id", BossAccountContext.id()));
    }

    /**
     * 根据ID获取配置
     */
    public BossConfigEntity getConfigById(Long id) {
        return bossConfigMapper.selectOne(new QueryWrapper<BossConfigEntity>().eq("id", id).eq("account_id", BossAccountContext.id()));
    }

    /**
     * 获取第一条配置（通常只有一条）
     */
    public BossConfigEntity getFirstConfig() {
        QueryWrapper<BossConfigEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("account_id", BossAccountContext.id());
        wrapper.last("LIMIT 1");
        return bossConfigMapper.selectOne(wrapper);
    }

    /**
     * 保存配置
     */
    public BossConfigEntity saveConfig(BossConfigEntity config) {
        config.setAccountId(BossAccountContext.id());
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        bossConfigMapper.insert(config);
        return config;
    }

    /**
     * 更新配置
     */
    public BossConfigEntity updateConfig(BossConfigEntity config) {
        if (getConfigById(config.getId()) == null) throw new IllegalArgumentException("配置不属于当前账号");
        config.setAccountId(BossAccountContext.id());
        config.setUpdatedAt(LocalDateTime.now());
        bossConfigMapper.updateById(config);
        return config;
    }

    /**
     * 保存或更新（优先更新第一条）配置，支持选择性更新（仅覆盖非空字段）。
     * - 若表中已有记录：合并非空字段并更新该记录
     * - 若表为空：插入新记录
     */
    public BossConfigEntity saveOrUpdateFirstSelective(BossConfigEntity partial) {
        BossConfigEntity existing = getFirstConfig();
        LocalDateTime now = LocalDateTime.now();
        // 仅显式页面设置可变更策略；其他配置的局部保存保留当前账号的薪资设置。
        var policy=partial.getSalaryPolicy()!=null?partial.getSalaryPolicy().validated():com.getjobs.worker.boss.BossSalaryPolicy.of(existing);
        partial.setSalaryPolicy(policy);partial.setSalaryFilterPolicy(policy.stored());
        var selected=salarySelection(partial.getSalary()!=null?partial.getSalary():existing==null?null:existing.getSalary(),policy);
        partial.setExpectedSalaryMin(selected.minimumK()==null?0:(int)Math.floor(selected.minimumK()));
        partial.setExpectedSalaryMax(selected.maximumK()==null?0:(int)Math.ceil(selected.maximumK()));

        if (existing == null) {
            // 表为空，插入新记录
            partial.setAccountId(BossAccountContext.id());
            partial.setId(null);
            partial.setCreatedAt(now);
            partial.setUpdatedAt(now);
            bossConfigMapper.insert(partial);
            return partial;
        }

        // 选择性合并：仅当请求体字段非空时才覆盖
        if (partial.getSayHi() != null) existing.setSayHi(partial.getSayHi());
        if (partial.getDebugger() != null) existing.setDebugger(partial.getDebugger());
        if (partial.getEnableAi() != null) existing.setEnableAi(partial.getEnableAi());
        if (partial.getFilterDeadHr() != null) existing.setFilterDeadHr(partial.getFilterDeadHr());
        if (partial.getSendImgResume() != null) existing.setSendImgResume(partial.getSendImgResume());
        if (partial.getAllowRepeatChat() != null) existing.setAllowRepeatChat(partial.getAllowRepeatChat());
        if (partial.getRepeatChatMessages() != null) existing.setRepeatChatMessages(partial.getRepeatChatMessages());
        if (partial.getWaitTime() != null) existing.setWaitTime(partial.getWaitTime());

        if (partial.getKeywords() != null) existing.setKeywords(partial.getKeywords());
        if (partial.getCityCode() != null) existing.setCityCode(partial.getCityCode());
        if (partial.getIndustry() != null) existing.setIndustry(partial.getIndustry());
        if (partial.getJobType() != null) existing.setJobType(partial.getJobType());
        if (partial.getExperience() != null) existing.setExperience(partial.getExperience());
        if (partial.getDegree() != null) existing.setDegree(partial.getDegree());
        if (partial.getSalary() != null) existing.setSalary(partial.getSalary());
        existing.setSalaryFilterPolicy(partial.getSalaryFilterPolicy());existing.setSalaryPolicy(policy);
        if (partial.getScale() != null) existing.setScale(partial.getScale());
        if (partial.getStage() != null) existing.setStage(partial.getStage());

        if (partial.getExpectedSalaryMin() != null) existing.setExpectedSalaryMin(partial.getExpectedSalaryMin());
        if (partial.getExpectedSalaryMax() != null) existing.setExpectedSalaryMax(partial.getExpectedSalaryMax());

        if (partial.getDeadStatus() != null) existing.setDeadStatus(partial.getDeadStatus());

        existing.setUpdatedAt(now);
        bossConfigMapper.updateById(existing);
        return existing;
    }

    /**
     * 删除配置
     */
    public boolean deleteConfig(Long id) {
        return bossConfigMapper.delete(new QueryWrapper<BossConfigEntity>().eq("id", id).eq("account_id", BossAccountContext.id())) > 0;
    }

    // ==================== 配置加载方法 ====================

    /**
     * 加载Boss配置
     * 从配置文件和数据库加载完整的Boss配置
     */
    public BossConfig loadBossConfig() {
        // 直接从数据库 boss_config 加载，并将括号列表解析为集合
        BossConfigEntity entity = getFirstConfig();
        BossConfig config = new BossConfig();

        if (entity == null) {
            log.warn("boss_config 表为空，使用默认空配置");
            return config;
        }

        // 文本与布尔/数值
        config.setSayHi(entity.getSayHi());
        config.setDebugger(entity.getDebugger() != null && entity.getDebugger() == 1);
        config.setEnableAI(entity.getEnableAi() != null && entity.getEnableAi() == 1);
        config.setFilterDeadHR(entity.getFilterDeadHr() != null && entity.getFilterDeadHr() == 1);
        config.setSendImgResume(entity.getSendImgResume() != null && entity.getSendImgResume() == 1);
        config.setAllowRepeatChat(Integer.valueOf(1).equals(entity.getAllowRepeatChat()));
        config.setRepeatChatMessages(entity.getRepeatChatMessages() != null
                ? entity.getRepeatChatMessages() : BossConfig.DEFAULT_REPEAT_CHAT_MESSAGES);
        config.setWaitTime(entity.getWaitTime() != null ? String.valueOf(entity.getWaitTime()) : null);

        // 关键词（允许逗号或括号列表），直接解析为列表
        config.setKeywords(parseListString(entity.getKeywords()));

        // 将中文名转换为代码，供 Worker 使用
        // 城市：单值或列表，统一转换为代码列表
        config.setCityCode(toCodes("city", parseListString(entity.getCityCode())));
        // 行业/经验/学历/规模/阶段：名称或代码 -> 统一为代码列表
        config.setIndustry(toCodes("industry", parseListString(entity.getIndustry())));
        config.setExperience(toCodes("experience", parseListString(entity.getExperience())));
        config.setDegree(toCodes("degree", parseListString(entity.getDegree())));
        config.setScale(toCodes("scale", parseListString(entity.getScale())));
        config.setStage(toCodes("stage", parseListString(entity.getStage())));

        // 职位类型：统一转换为代码，若为列表取第一个；为空时使用不限代码
        List<String> jobTypeCodes = toCodes("jobType", parseListString(entity.getJobType()));
        String jobTypeCode = null;
        if (!jobTypeCodes.isEmpty()) {
            jobTypeCode = jobTypeCodes.get(0);
        } else if (entity.getJobType() != null && !entity.getJobType().trim().isEmpty()) {
            BossOptionEntity byCode = getOptionByTypeAndCode("jobType", entity.getJobType());
            jobTypeCode = byCode != null && byCode.getCode() != null
                    ? byCode.getCode()
                    : getCodeByTypeAndName("jobType", entity.getJobType());
        }
        if (jobTypeCode == null || jobTypeCode.trim().isEmpty()) {
            jobTypeCode = com.getjobs.worker.utils.Constant.UNLIMITED_CODE;
        }
        config.setJobType(jobTypeCode);
        // 薪资：名称或代码 -> 统一为代码列表（用于URL逗号拼接）
        config.setSalary(toCodes("salary", parseListString(entity.getSalary())));

        var salaryPolicy=com.getjobs.worker.boss.BossSalaryPolicy.of(entity);
        config.setSalarySelection(salarySelection(entity.getSalary(),salaryPolicy));
        var selected=config.getSalarySelection();
        config.setExpectedSalary(java.util.Arrays.asList(
                selected.minimumK()==null?0:(int)Math.floor(selected.minimumK()),
                selected.maximumK()==null?0:(int)Math.ceil(selected.maximumK())));
        if(selected.unrestricted())config.setSalary(java.util.List.of(com.getjobs.worker.utils.Constant.UNLIMITED_CODE));
        else if("custom".equals(salaryPolicy.mode()))config.setSalary(salaryPolicy.searchCodes(getOptionsByType("salary")));

        // HR不在线状态（括号列表字符串）
        config.setDeadStatus(parseListString(entity.getDeadStatus()));

        log.info("已从 boss_config 加载Boss配置，并完成括号列表解析");
        return config;
    }

    public com.getjobs.worker.boss.BossSalarySelection salarySelection(String raw) {
        return salarySelection(raw,com.getjobs.worker.boss.BossSalaryPolicy.defaults());
    }
    public com.getjobs.worker.boss.BossSalarySelection salarySelection(String raw,com.getjobs.worker.boss.BossSalaryPolicy policy) {
        return policy.selection(raw,code -> {
            var option=getOptionByTypeAndCode("salary",code);
            return option==null?null:option.getName();
        });
    }

    /**
     * 解析括号列表或逗号分隔的字符串为列表，例如 "[a,b,c]" 或 "a,b,c"。
     * 空值返回空列表。
     */
    public List<String> parseListString(String raw) {
        if (raw == null || raw.trim().isEmpty()) return java.util.Collections.emptyList();
        String s = raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.trim().isEmpty()) return java.util.Collections.emptyList();
        return java.util.Arrays.stream(s.split(","))
                .map(String::trim)
                // 去除项内可能存在的双引号，兼容 JSON 数组序列化存储
                .map(str -> str.replaceAll("^\"|\"$", ""))
                .filter(str -> !str.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 将列表转换为括号列表字符串，例如 [a,b,c]
     */
    public String toBracketListString(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        return "[" + String.join(",", list) + "]";
    }

    /**
     * 规范化：将传入的代码列表转换为名称列表（若传入已是名称则原样返回）。
     */
    public List<String> toNames(String type, List<String> items) {
        if (items == null || items.isEmpty()) return java.util.Collections.emptyList();
        return items.stream().map(it -> {
            // 若是有效code，直接按code查找并取name
            BossOptionEntity byCode = getOptionByTypeAndCode(type, it);
            if (byCode != null && byCode.getName() != null) {
                return byCode.getName();
            }
            // 否则当作name使用（无需转换）
            return it;
        }).collect(Collectors.toList());
    }

    /**
     * 规范化：将传入的名称列表转换为代码列表（若传入已是代码则原样保留）。
     */
    public List<String> toCodes(String type, List<String> items) {
        if (items == null || items.isEmpty()) return java.util.Collections.emptyList();
        return items.stream().map(it -> {
            // 若是有效code，保留
            BossOptionEntity byCode = getOptionByTypeAndCode(type, it);
            if (byCode != null && byCode.getCode() != null) {
                return byCode.getCode();
            }
            // 否则按name查code
            String code = getCodeByTypeAndName(type, it);
            return code != null ? code : "0";
        }).collect(Collectors.toList());
    }

    /**
     * 统一化城市：将 city 值（可能是 code 或 name 或括号列表）转换为『城市中文名』。
     */
    public String normalizeCityToName(String raw) {
        List<String> list = parseListString(raw);
        String first = list.isEmpty() ? (raw == null ? "" : raw.trim()) : list.get(0);
        if (first.isEmpty()) return "";
        BossOptionEntity byCode = getOptionByTypeAndCode("city", first);
        if (byCode != null && byCode.getName() != null) return byCode.getName();
        // 已是中文名
        return first;
    }

    public String normalizeCitySequence(String raw) {
        List<String> values=parseListString(raw==null?null:raw.replace('，',','));
        if(values.isEmpty() || values.size()>20)throw new IllegalArgumentException("请选择 1 至 20 个城市，并按投递顺序排列");
        List<String> names=new ArrayList<>();
        for(String value:values) {
            var byCode=getOptionByTypeAndCode("city",value);
            var option=byCode!=null?byCode:bossOptionMapper.selectOne(new QueryWrapper<BossOptionEntity>().eq("type","city").eq("name",value).last("LIMIT 1"));
            if(option==null)throw new IllegalArgumentException("无法识别城市："+value+"，请从城市选项中选择");
            if(!names.contains(option.getName()))names.add(option.getName());
        }
        if(names.size()>1 && names.stream().anyMatch(n->"全国".equals(n)||"不限".equals(n)))throw new IllegalArgumentException("全国或不限须单独使用，不能与具体城市混排");
        return names.size()==1?names.getFirst():toBracketListString(names);
    }

    /** 项目记录中的既有投递，用于统计新增岗位；不改变重复沟通开关。 */
    public boolean wasDelivered(com.getjobs.worker.boss.review.BossReviewJob job) {
        String id=job.id().isBlank()?com.getjobs.worker.boss.review.BossCandidateCollector.idFromUrl(job.detailUrl()):job.id();
        return !id.isBlank() && bossJobDataMapper.selectCount(new QueryWrapper<BossJobDataEntity>().eq("account_id",BossAccountContext.id()).eq("encrypt_id",id).eq("delivery_status","已投递"))>0;
    }

    // ==================== Blacklist相关方法 ====================

    /**
     * 根据类型获取黑名单列表
     *
     * @param type 类型 (company/recruiter/job)
     * @return 黑名单值集合
     */
    public Set<String> getBlacklistByType(String type) {
        LambdaQueryWrapper<BlacklistEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BlacklistEntity::getAccountId, BossAccountContext.id());
        if ("company".equals(type)) wrapper.in(BlacklistEntity::getType, List.of("company", "boss"));
        else wrapper.eq(BlacklistEntity::getType, type);
        List<BlacklistEntity> list = blacklistMapper.selectList(wrapper);
        return list.stream()
                .map(BlacklistEntity::getValue)
                .filter(java.util.Objects::nonNull).map(String::strip).filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
    }

    /**
     * 获取所有公司黑名单
     */
    public Set<String> getBlackCompanies() {
        return getBlacklistByType("company");
    }

    /**
     * 获取所有招聘者黑名单
     */
    public Set<String> getBlackRecruiters() {
        return getBlacklistByType("recruiter");
    }

    /**
     * 获取所有职位黑名单
     */
    public Set<String> getBlackJobs() {
        return getBlacklistByType("job");
    }

    /**
     * 添加黑名单
     *
     * @param type  类型
     * @param value 值
     * @return 是否成功
     */
    public boolean addBlacklist(String type, String value) {
        // 检查是否已存在
        LambdaQueryWrapper<BlacklistEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BlacklistEntity::getAccountId, BossAccountContext.id());
        wrapper.eq(BlacklistEntity::getType, type)
                .eq(BlacklistEntity::getValue, value);
        if (blacklistMapper.selectCount(wrapper) > 0) {
            return false; // 已存在
        }

        BlacklistEntity entity = new BlacklistEntity();
        entity.setAccountId(BossAccountContext.id());
        entity.setType(type);
        entity.setValue(value);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return blacklistMapper.insert(entity) > 0;
    }

    /**
     * 批量添加黑名单
     *
     * @param type   类型
     * @param values 值集合
     */
    public void addBlacklistBatch(String type, Set<String> values) {
        values.forEach(value -> addBlacklist(type, value));
    }

    /**
     * 删除黑名单
     *
     * @param type  类型
     * @param value 值
     * @return 是否成功
     */
    public boolean removeBlacklist(String type, String value) {
        LambdaQueryWrapper<BlacklistEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BlacklistEntity::getAccountId, BossAccountContext.id());
        wrapper.eq(BlacklistEntity::getType, type)
                .eq(BlacklistEntity::getValue, value);
        return blacklistMapper.delete(wrapper) > 0;
    }

    /**
     * 获取所有黑名单
     *
     * @return 所有黑名单实体
     */
    public List<BlacklistEntity> getAllBlacklist() {
        return blacklistMapper.selectList(new LambdaQueryWrapper<BlacklistEntity>().eq(BlacklistEntity::getAccountId, BossAccountContext.id()));
    }

    // ==================== boss_data（岗位数据）相关方法 ====================

    /**
     * 确保 boss_data 表的列顺序以 encrypt_id、encrypt_user_id 开头。
     * 若不满足，则进行一次在线迁移：创建新表、复制数据、替换旧表。
     */
    public void ensureBossDataColumnOrder() {
        java.sql.Connection conn = null;
        try {
            conn = dataSource.getConnection();
            try (java.sql.Statement stmt = conn.createStatement()) {
                java.util.List<String> cols = new java.util.ArrayList<>();
                try (java.sql.ResultSet rs = stmt.executeQuery("PRAGMA table_info('boss_data')")) {
                    while (rs.next()) {
                        cols.add(rs.getString("name"));
                    }
                }
                if (cols.contains("account_id")) return; // 多账号迁移后禁止旧的重建逻辑丢弃账号列
                if (cols.isEmpty()) return; // 表不存在或无列
                boolean needMigrate = true;
                if (cols.size() >= 3) {
                    String c0 = cols.get(0) == null ? "" : cols.get(0).toLowerCase();
                    String c1 = cols.get(1) == null ? "" : cols.get(1).toLowerCase();
                    String c2 = cols.get(2) == null ? "" : cols.get(2).toLowerCase();
                    // 允许第一列是 id 或 encrypt_id，但要求前两列满足 encrypt_id、encrypt_user_id 顺序
                    if ("id".equals(c0) && "encrypt_id".equals(c1) && "encrypt_user_id".equals(c2)) {
                        needMigrate = false;
                    } else if ("encrypt_id".equals(c0) && "encrypt_user_id".equals(c1)) {
                        needMigrate = false;
                    }
                }
                if (!needMigrate) return;

                stmt.execute("BEGIN TRANSACTION");
                // 新表：将 encrypt_id、encrypt_user_id 移到最前（紧随 id）
                String createSql = "CREATE TABLE boss_data_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "encrypt_id TEXT, " +
                        "encrypt_user_id TEXT, " +
                        "company_name TEXT, " +
                        "job_name TEXT, " +
                        "salary TEXT, " +
                        "location TEXT, " +
                        "experience TEXT, " +
                        "degree TEXT, " +
                        "hr_name TEXT, " +
                        "hr_position TEXT, " +
                        "hr_active_status TEXT, " +
                        "delivery_status TEXT, " +
                        "job_description TEXT, " +
                        "job_url TEXT, " +
                        "recruitment_status TEXT, " +
                        "company_address TEXT, " +
                        "industry TEXT, " +
                        "introduce TEXT, " +
                        "financing_stage TEXT, " +
                        "company_scale TEXT, " +
                        "created_at TEXT, " +
                        "updated_at TEXT" +
                        ")";
                stmt.execute(createSql);

                String copySql = "INSERT INTO boss_data_new (" +
                        "id, encrypt_id, encrypt_user_id, company_name, job_name, salary, location, experience, degree, " +
                        "hr_name, hr_position, hr_active_status, delivery_status, job_description, job_url, recruitment_status, " +
                        "company_address, industry, introduce, financing_stage, company_scale, created_at, updated_at" +
                        ") SELECT " +
                        "id, encrypt_id, encrypt_user_id, company_name, job_name, salary, location, experience, degree, " +
                        "hr_name, hr_position, hr_active_status, delivery_status, job_description, job_url, recruitment_status, " +
                        "company_address, industry, introduce, financing_stage, company_scale, created_at, updated_at " +
                        "FROM boss_data";
                stmt.execute(copySql);

                stmt.execute("DROP TABLE boss_data");
                stmt.execute("ALTER TABLE boss_data_new RENAME TO boss_data");
                stmt.execute("COMMIT");
                log.info("已调整 boss_data 表列顺序：将 encrypt_id、encrypt_user_id 前置");
            }
        } catch (Exception e) {
            log.warn("调整 boss_data 列顺序失败：{}", e.getMessage());
            try { if (conn != null) conn.createStatement().execute("ROLLBACK"); } catch (Exception ignore) {}
        } finally {
            try { if (conn != null) conn.close(); } catch (Exception ignore) {}
        }
    }

    /**
     * 判断岗位是否已存在（相同 encrypt_id AND encrypt_user_id）
     */
    public boolean existsBossJob(String encryptId, String encryptUserId) {
        if (encryptId == null || encryptUserId == null) return false;
        QueryWrapper<BossJobDataEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("account_id", BossAccountContext.id());
        wrapper.eq("encrypt_id", encryptId)
                .eq("encrypt_user_id", encryptUserId)
                .last("LIMIT 1");
        Long count = bossJobDataMapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    /**
     * 仅根据 encrypt_id 判断是否存在（当 encrypt_user_id 缺失时的降级策略）
     */
    public boolean existsBossJobByEncryptId(String encryptId) {
        if (encryptId == null) return false;
        QueryWrapper<BossJobDataEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("account_id", BossAccountContext.id());
        wrapper.eq("encrypt_id", encryptId)
                .last("LIMIT 1");
        Long count = bossJobDataMapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    /**
     * 插入新岗位数据（默认 delivery_status 为 未投递，或外部传入值）
     */
    public void insertBossJob(BossJobDataEntity entity) {
        if (entity == null) return;
        LocalDateTime now = LocalDateTime.now();
        entity.setAccountId(BossAccountContext.id());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        bossJobDataMapper.insert(entity);
    }

    /** 精准采集没有点击沟通；按账号和岗位ID更新资料，保留既有成功投递记录。 */
    public void recordPrecisionJob(BossReviewJob job, String status, String reason, LocalDateTime deliveredAt) {
        String encryptId = job.id().isBlank() ? BossCandidateCollector.idFromUrl(job.detailUrl()) : job.id();
        if (encryptId.isBlank() || job.detailUrl().isBlank()) return;
        var existing = bossJobDataMapper.selectOne(new QueryWrapper<BossJobDataEntity>()
                .eq("account_id", BossAccountContext.id()).eq("encrypt_id", encryptId).last("LIMIT 1"));
        var entity = new BossJobDataEntity(); entity.setEncryptId(encryptId); entity.setAccountId(BossAccountContext.id());
        if (!job.title().isBlank()) entity.setJobName(job.title());
        if (!job.company().isBlank()) entity.setCompanyName(job.company());
        if (!job.companyScale().isBlank()) entity.setCompanyScale(job.companyScale());
        if (!job.city().isBlank()) entity.setLocation(job.city());
        if (!job.salary().isBlank()) entity.setSalary(job.salary());
        if (!job.experience().isBlank()) entity.setExperience(job.experience());
        if (!job.degree().isBlank()) entity.setDegree(job.degree());
        if (!job.recruiterTitle().isBlank()) entity.setHrPosition(job.recruiterTitle());
        if (!job.activeStatus().isBlank()) entity.setHrActiveStatus(job.activeStatus());
        entity.setJobUrl(job.detailUrl());
        if (!job.description().isBlank()) entity.setJobDescription(job.description());
        if (existing == null || !"已投递".equals(existing.getDeliveryStatus()) || deliveredAt != null) {
            entity.setDeliveryStatus(status);
            entity.setDeliveryReason(reason);
            if (deliveredAt != null) entity.setDeliveredAt(deliveredAt.truncatedTo(ChronoUnit.SECONDS));
        }
        if (existing == null) insertBossJob(entity);
        else { entity.setId(existing.getId()); entity.setUpdatedAt(LocalDateTime.now()); bossJobDataMapper.updateById(entity); }
    }

    /**
     * 更新投递状态（WHERE encrypt_id = ? AND encrypt_user_id = ?）
     */
    public void updateDeliveryStatus(String encryptId, String encryptUserId, String status,
                                     String reason, LocalDateTime deliveredAt) {
        if (encryptId == null || status == null) return;
        BossJobDataEntity update = new BossJobDataEntity();
        update.setDeliveryStatus(status);
        update.setDeliveryReason(reason);
        if (deliveredAt != null) update.setDeliveredAt(deliveredAt.truncatedTo(ChronoUnit.SECONDS));
        update.setUpdatedAt(LocalDateTime.now());
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BossJobDataEntity> uw =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
        uw.eq("account_id", BossAccountContext.id());
        uw.eq("encrypt_id", encryptId);
        if (encryptUserId != null) {
            uw.eq("encrypt_user_id", encryptUserId);
        }
        if (!"已投递".equals(status)) {
            uw.and(w -> w.isNull("delivery_status").or().ne("delivery_status", "已投递"));
        }
        bossJobDataMapper.update(update, uw);
    }

    // ==================== 投递分析（Dashboard）相关方法 ====================

    /**
     * 薪资解析结果
     */
    public static class SalaryInfo {
        public Double minK;      // 最小K（单位：K/月）
        public Double maxK;      // 最大K（单位：K/月）
        public Integer months;    // 月数（默认12）
        public Double medianK;    // 中位数K
        public Long annualTotal;  // 年包（单位：元）
    }

    /**
     * 解析薪资字符串，支持示例：
     *  - 20-40K
     *  - 35-65K·16薪
     *  - 30K·15薪
     *  - 面议（返回null）
     */
    public static SalaryInfo parseSalary(String salary) {
        var monthly = com.getjobs.worker.boss.BossSalary.parse(salary);
        if (monthly == null) return null;
        SalaryInfo info = new SalaryInfo();
        info.minK = monthly.minK();
        info.maxK = monthly.maxK();
        info.months = monthly.months();
        info.medianK = (info.minK + info.maxK) / 2.0;
        info.annualTotal = Math.round(info.medianK * 1000 * info.months);
        return info;
    }

    /** KPI 指标 */
    public static class Kpi {
        public long total;
        public long delivered;
        public long pending;
        public long filtered;
        public long failed;
        public Double avgMonthlyK; // 平均中位数K
    }

    /** 通用 name-value 项 */
    public static class NameValue {
        public String name;
        public long value;
        public NameValue() {}
        public NameValue(String name, long value) { this.name = name; this.value = value; }
    }

    /** 薪资桶 */
    public static class BucketValue {
        public String bucket;
        public long value;
        public BucketValue() {}
        public BucketValue(String bucket, long value) { this.bucket = bucket; this.value = value; }
    }

    /** 图表集合 */
    public static class Charts {
        public List<NameValue> byStatus;
        public List<NameValue> byCity;
        public List<NameValue> byIndustry;
        public List<NameValue> byCompany;
        public List<NameValue> byExperience;
        public List<NameValue> byDegree;
        public List<BucketValue> salaryBuckets;
        public List<NameValue> dailyTrend; // date 作为 name
        public List<NameValue> hrActivity;
    }

    /** 统计响应 */
    public static class StatsResponse {
        public Kpi kpi;
        public Charts charts;
    }

    /** 列表分页响应 */
    public static class PagedResult {
        public List<BossJobDataEntity> items;
        public long total;
        public int page;
        public int size;
    }

    /**
     * 获取投递分析统计与图表数据
     */
    public StatsResponse getBossStats() {
        return getBossStats(null, null, null, null, null, null, null, false);
    }

    /**
     * 获取投递分析统计与图表数据（按筛选条件）
     */
    public StatsResponse getBossStats(
            List<String> statuses,
            String location,
            String experience,
            String degree,
            Double minK,
            Double maxK,
            String keyword,
            boolean filterHeadhunter
    ) {
        StatsResponse resp = new StatsResponse();
        resp.kpi = new Kpi();
        Charts charts = new Charts();
        charts.byStatus = new ArrayList<>();
        charts.byCity = new ArrayList<>();
        charts.byIndustry = new ArrayList<>();
        charts.byCompany = new ArrayList<>();
        charts.byExperience = new ArrayList<>();
        charts.byDegree = new ArrayList<>();
        charts.salaryBuckets = new ArrayList<>();
        charts.dailyTrend = new ArrayList<>();
        charts.hrActivity = new ArrayList<>();

        try {
            var wrapper = jobHistoryFilters(statuses, location, experience, degree, keyword, filterHeadhunter);
            wrapper.orderByDesc("created_at", "id");
            List<BossJobDataEntity> all = bossJobDataMapper.selectList(wrapper);

            // 内存进行薪资区间过滤（按中位数K）
            List<BossJobDataEntity> filtered = new ArrayList<>();
            double sumMedian = 0.0; long countMedian = 0;
            for (BossJobDataEntity e : all) {
                SalaryInfo info = parseSalary(e.getSalary());
                boolean passSalary = matchesHistorySalary(info, minK, maxK);
                if (passSalary) {
                    filtered.add(e);
                    if (info != null && info.medianK != null) { sumMedian += info.medianK; countMedian++; }
                }
            }

            // KPI
            resp.kpi.total = filtered.size();
            resp.kpi.delivered = filtered.stream().filter(e -> "已投递".equals(e.getDeliveryStatus())).count();
            resp.kpi.pending = filtered.stream().filter(e -> "未投递".equals(e.getDeliveryStatus())).count();
            resp.kpi.filtered = filtered.stream().filter(e -> "已过滤".equals(e.getDeliveryStatus())).count();
            resp.kpi.failed = filtered.stream().filter(e -> "投递失败".equals(e.getDeliveryStatus())).count();
            resp.kpi.avgMonthlyK = countMedian > 0 ? Math.round((sumMedian / countMedian) * 100.0) / 100.0 : null;

            // Charts - 分组聚合（在内存中统计）
            Map<String, Long> byStatus = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getDeliveryStatus()), Collectors.counting()));
            byStatus.forEach((k, v) -> charts.byStatus.add(new NameValue(k, v)));

            Map<String, Long> byCity = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getLocation()), Collectors.counting()));
            byCity.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(10)
                    .forEach(en -> charts.byCity.add(new NameValue(en.getKey(), en.getValue())));

            // byIndustry TOP10
            Map<String, Long> byIndustry = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getIndustry()), Collectors.counting()));
            byIndustry.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(10)
                    .forEach(en -> charts.byIndustry.add(new NameValue(en.getKey(), en.getValue())));

            Map<String, Long> byCompany = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getCompanyName()), Collectors.counting()));
            byCompany.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(10)
                    .forEach(en -> charts.byCompany.add(new NameValue(en.getKey(), en.getValue())));

            Map<String, Long> byExp = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getExperience()), Collectors.counting()));
            byExp.forEach((k, v) -> charts.byExperience.add(new NameValue(k, v)));

            Map<String, Long> byDeg = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getDegree()), Collectors.counting()));
            byDeg.forEach((k, v) -> charts.byDegree.add(new NameValue(k, v)));

            // dailyTrend（created_at 到天）
            Map<String, Long> byDay = filtered.stream()
                    .collect(Collectors.groupingBy(e -> {
                        LocalDateTime t = e.getCreatedAt();
                        return t == null ? "未知" : String.format("%04d-%02d-%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth());
                    }, Collectors.counting()));
            byDay.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(en -> charts.dailyTrend.add(new NameValue(en.getKey(), en.getValue())));

            // hrActivity（活跃状态非空的 hr_name 计数）
            Map<String, Long> hrAct = filtered.stream()
                    .filter(e -> e.getHrActiveStatus() != null && !e.getHrActiveStatus().trim().isEmpty())
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getHrName()), Collectors.counting()));
            hrAct.forEach((k, v) -> charts.hrActivity.add(new NameValue(k, v)));

            // salaryBuckets（动态上限）
            long b0_10=0,b10_15=0,b15_20=0,b20_top=0,b_ge_top=0;
            double maxMedian = 0.0;
            List<Double> medians = new ArrayList<>();
            for (BossJobDataEntity e : filtered) {
                SalaryInfo info = parseSalary(e.getSalary());
                if (info == null || info.medianK == null) continue;
                double m = info.medianK;
                medians.add(m);
                if (m > maxMedian) maxMedian = m;
            }
            int topEdge = (int) Math.ceil(maxMedian / 5.0) * 5;
            if (topEdge <= 20) topEdge = 25;
            for (double m : medians) {
                if (m < 10) b0_10++;
                else if (m < 15) b10_15++;
                else if (m < 20) b15_20++;
                else if (m < topEdge) b20_top++;
                else b_ge_top++;
            }
            charts.salaryBuckets.add(new BucketValue("0-10K", b0_10));
            charts.salaryBuckets.add(new BucketValue("10-15K", b10_15));
            charts.salaryBuckets.add(new BucketValue("15-20K", b15_20));
            charts.salaryBuckets.add(new BucketValue("20-" + topEdge + "K", b20_top));
            charts.salaryBuckets.add(new BucketValue(">=" + topEdge + "K", b_ge_top));

            resp.charts = charts;
            return resp;
        } catch (Exception e) {
            log.error("获取Boss筛选统计失败: {}", e.getMessage(), e);
            resp.charts = charts;
            return resp;
        }
    }

    private long scalarCount(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private String nullSafe(String s) { return s == null || s.isEmpty() ? "未知" : s; }

    /**
     * 列表查询（分页 + 筛选 + 关键词 + 薪资区间基于中位数K）
     */
    public PagedResult listBossJobs(
            List<String> statuses,
            String location,
            String experience,
            String degree,
            Double minK,
            Double maxK,
            String keyword,
            int page,
            int size,
            boolean filterHeadhunter
    ) {
        page = Math.max(1, page);
        if (size <= 0) size = 20;

        var wrapper = jobHistoryFilters(statuses, location, experience, degree, keyword, filterHeadhunter);
        PagedResult result = new PagedResult();
        result.size = size;
        if (minK == null && maxK == null) {
            result.total = bossJobDataMapper.selectCount(wrapper);
            result.page = validHistoryPage(page, size, result.total);
            long offset = (long) (result.page - 1) * size;
            result.items = result.total == 0 ? List.of() : bossJobDataMapper.selectList(
                    wrapper.orderByDesc("created_at", "id").last("LIMIT " + size + " OFFSET " + offset));
        } else {
            var filtered = filterHistorySalary(wrapper, minK, maxK);
            result.total = filtered.size();
            result.page = validHistoryPage(page, size, result.total);
            int from = (result.page - 1) * size;
            result.items = filtered.subList(from, (int) Math.min(result.total, (long) from + size));
        }
        return result;
    }

    public List<BossJobDataEntity> exportBossJobs(List<String> statuses, String location, String experience,
                                                String degree, Double minK, Double maxK, String keyword,
                                                boolean filterHeadhunter) {
        return filterHistorySalary(jobHistoryFilters(statuses, location, experience, degree, keyword,
                filterHeadhunter), minK, maxK);
    }

    private static int validHistoryPage(int page, int size, long total) {
        long lastPage = total == 0 ? 1 : 1 + (total - 1) / size;
        return (int) Math.min(page, lastPage);
    }

    private QueryWrapper<BossJobDataEntity> jobHistoryFilters(List<String> statuses, String location,
                                                             String experience, String degree, String keyword,
                                                             boolean filterHeadhunter) {
        QueryWrapper<BossJobDataEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("account_id", BossAccountContext.id());
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in("delivery_status", statuses);
        }
        if (StringUtils.isNotBlank(location)) wrapper.eq("location", location);
        if (StringUtils.isNotBlank(experience)) wrapper.eq("experience", experience);
        if (StringUtils.isNotBlank(degree)) wrapper.eq("degree", degree);

        if (StringUtils.isNotBlank(keyword)) {
            wrapper.and(w -> w.like("company_name", keyword)
                    .or().like("job_name", keyword)
                    .or().like("hr_name", keyword));
        }

        if (filterHeadhunter) {
            wrapper.and(w -> w.isNull("hr_position").or(n ->
                    n.notLike("hr_position", "猎头").notLike("hr_position", "獵頭")));
        }
        return wrapper;
    }

    private List<BossJobDataEntity> filterHistorySalary(QueryWrapper<BossJobDataEntity> wrapper,
                                                       Double minK, Double maxK) {
        var jobs = bossJobDataMapper.selectList(wrapper.orderByDesc("created_at", "id"));
        if (minK == null && maxK == null) return jobs;
        return jobs.stream().filter(job -> matchesHistorySalary(parseSalary(job.getSalary()), minK, maxK)).toList();
    }

    private static boolean matchesHistorySalary(SalaryInfo salary, Double minK, Double maxK) {
        if (minK == null && maxK == null) return true;
        return salary != null && salary.medianK != null
                && (minK == null || salary.medianK >= minK) && (maxK == null || salary.medianK <= maxK);
    }

    /**
     * 刷新数据：执行列顺序检查，并执行 VACUUM 以优化数据库；返回当前总数
     */
    public Map<String, Object> reloadBossData() {
        Map<String, Object> resp = new HashMap<>();
        Connection conn = null;
        try {
            ensureBossDataColumnOrder();
            conn = dataSource.getConnection();
            try (Statement st = conn.createStatement()) {
                try { st.execute("PRAGMA wal_checkpoint(TRUNCATE)"); } catch (Exception ignore) {}
                try { st.execute("VACUUM"); } catch (Exception ignore) {}
            }
            long total = scalarCount(conn, "SELECT COUNT(*) FROM boss_data WHERE account_id=" + BossAccountContext.id());
            resp.put("success", true);
            resp.put("message", "刷新完成");
            resp.put("total", total);
        } catch (Exception e) {
            log.warn("刷新boss_data失败: {}", e.getMessage());
            resp.put("success", false);
            resp.put("message", "刷新失败: " + e.getMessage());
        } finally {
            try { if (conn != null) conn.close(); } catch (Exception ignore) {}
        }
        return resp;
    }
}
