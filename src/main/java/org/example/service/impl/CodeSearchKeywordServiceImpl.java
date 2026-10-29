package org.example.service.impl;

import com.ct.common.core.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.example.domain.dto.CodeSearchTermsDTO;
import org.example.domain.dto.OpenChatMessageDTO;
import org.example.service.CodeSearchKeywordService;
import org.example.service.OpenCodeService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码扫描关键词提炼与搜索 Token 构建服务实现类
 *
 * @author zeymo
 */
@Slf4j
@Service
public class CodeSearchKeywordServiceImpl implements CodeSearchKeywordService {

    @Resource
    private OpenCodeService openCodeService;

    // 前置无意义引导词模式
    private static final Pattern PREFIX_PATTERN = Pattern.compile(
            "^(?:请问|请帮我|帮我|如何|怎样|怎么|梳理|分析|查找|查看|查询|搜索|扫描|定位|介绍|关于|想了解)\\s*"
    );

    // 后置疑问及泛化询问句式模式
    private static final Pattern SUFFIX_PATTERN = Pattern.compile(
            "(?:有几张数据库表|有几张表|有多少张表|有多少表|几张表|多少张表|共有几张表|涉及的表名是哪些|涉及的表名有哪些|涉及的表是哪些|涉及哪些表|涉及的表有哪些|涉及的表|用到了哪些表|用到了什么表|对应哪些表|对应什么表|相关的表|相关的数据库表|相关表|表名是哪些|表名有哪些|有哪些表名|有哪些状态|状态有哪些|状态是哪些|是什么状态|有哪些字段|字段有哪些|字段是哪些|有哪些类型|类型有哪些|类型是哪些|使用的地方|在哪些地方用到了|的具体实现|相关的代码|的核心代码|的代码在哪里|的代码|的核心逻辑|的逻辑|的使用情况|使用情况|的流程|有哪些校验|校验逻辑有哪些|的校验逻辑|的校验|是怎么实现的|怎么实现的|是怎么处理的|怎么处理的|是怎么写的|怎么写的|怎么做的|怎么算|是怎么算的|是什么|是哪些|有哪些|逻辑|流程|规则|实现|机制)+[?？!！。\\s]*$"
    );

    // 常用电商与供应链业务中英文对照表
    private static final Map<String, List<String>> BUSINESS_DICT = new HashMap<>();

    static {
        // 调拨
        BUSINESS_DICT.put("调拨", Arrays.asList("allocation", "allocationorder", "allocation_order", "diaobo", "transfer"));
        BUSINESS_DICT.put("调拨单", Arrays.asList("allocation", "allocationorder", "allocation_order", "diaobo", "transfer"));

        // 实报 / 报销
        BUSINESS_DICT.put("实报", Arrays.asList("actual", "actualreport", "reimbursement", "actualreimbursement", "actual_reimbursement", "shibao"));
        BUSINESS_DICT.put("报销", Arrays.asList("actual", "actualreport", "reimbursement", "actualreimbursement", "actual_reimbursement", "shibao"));

        // 盘点
        BUSINESS_DICT.put("盘点", Arrays.asList("inventorycheck", "stocktake", "checkorder", "inventory_check", "pandian"));
        BUSINESS_DICT.put("盘点单", Arrays.asList("inventorycheck", "stocktake", "checkorder", "inventory_check", "pandian"));

        // 调整
        BUSINESS_DICT.put("调整", Arrays.asList("adjustment", "adjustmentorder", "adjustment_order", "adjust"));
        BUSINESS_DICT.put("调整单", Arrays.asList("adjustment", "adjustmentorder", "adjustment_order", "adjust"));

        // 库存
        BUSINESS_DICT.put("库存", Arrays.asList("inventory", "stock", "statement", "details", "kucun"));
        BUSINESS_DICT.put("结存", Arrays.asList("balance", "statement", "inventory_details"));

        // 头程 / 账单
        BUSINESS_DICT.put("头程", Arrays.asList("headbill", "head_bill", "firstleg", "head"));
        BUSINESS_DICT.put("头程账单", Arrays.asList("headbill", "head_bill", "firstleg"));
        BUSINESS_DICT.put("账单", Arrays.asList("bill", "statement", "reconciliation"));
        BUSINESS_DICT.put("对账", Arrays.asList("reconciliation", "bill", "statement"));
        BUSINESS_DICT.put("对账单", Arrays.asList("statement", "statementaccount", "statement_account", "reconciliation"));

        // 拆分 / 分摊 / 明细（工程中存在 spilt 拼写，需同时覆盖）
        BUSINESS_DICT.put("拆分", Arrays.asList("split", "spilt"));
        BUSINESS_DICT.put("分摊", Arrays.asList("share", "apportion", "allocate"));
        BUSINESS_DICT.put("明细", Arrays.asList("detail"));

        // 采购
        BUSINESS_DICT.put("采购", Arrays.asList("purchase", "purchaseorder", "purchase_order", "payable", "caigou"));
        BUSINESS_DICT.put("采购单", Arrays.asList("purchase", "purchaseorder", "purchase_order", "payable", "caigou"));

        // 销售
        BUSINESS_DICT.put("销售", Arrays.asList("sale", "sales", "saleorder", "receivable", "xiaoshou"));
        BUSINESS_DICT.put("销售单", Arrays.asList("sale", "sales", "saleorder", "receivable", "xiaoshou"));

        // 出入库
        BUSINESS_DICT.put("入库", Arrays.asList("inbound", "instock", "ruku"));
        BUSINESS_DICT.put("出库", Arrays.asList("outbound", "outstock", "chuku"));

        // 物流 / 货代
        BUSINESS_DICT.put("物流", Arrays.asList("logistics", "shipping", "forwarder", "carrier"));
        BUSINESS_DICT.put("箱号", Arrays.asList("box", "carton", "container"));
        BUSINESS_DICT.put("提单", Arrays.asList("billoflading", "lading", "bill"));

        // 供应商 / 客户
        BUSINESS_DICT.put("供应商", Arrays.asList("supplier", "vendor"));
        BUSINESS_DICT.put("客户", Arrays.asList("customer", "client"));

        // 领星
        BUSINESS_DICT.put("领星", Arrays.asList("lingxing", "lx", "ods_lx"));

        // 发票
        BUSINESS_DICT.put("发票", Arrays.asList("invoice"));

        // 流水 / 日志
        BUSINESS_DICT.put("流水", Arrays.asList("statement", "flow"));
        BUSINESS_DICT.put("操作日志", Arrays.asList("operation_log", "log"));
        BUSINESS_DICT.put("日志", Arrays.asList("log", "operation_log"));
    }

    // 动作词典：仅用于方法名打分，不参与文件检索（add/list 等在代码中过于常见）
    private static final Map<String, List<String>> ACTION_DICT = new LinkedHashMap<>();

    static {
        ACTION_DICT.put("新增", Arrays.asList("add", "save", "insert", "create"));
        ACTION_DICT.put("添加", Arrays.asList("add", "save", "insert"));
        ACTION_DICT.put("创建", Arrays.asList("create", "add", "save"));
        ACTION_DICT.put("保存", Arrays.asList("save", "add"));
        ACTION_DICT.put("修改", Arrays.asList("update", "edit", "modify"));
        ACTION_DICT.put("编辑", Arrays.asList("update", "edit", "modify"));
        ACTION_DICT.put("更新", Arrays.asList("update", "modify", "refresh"));
        ACTION_DICT.put("删除", Arrays.asList("delete", "remove"));
        ACTION_DICT.put("查询", Arrays.asList("list", "query", "page", "search", "select"));
        ACTION_DICT.put("列表", Arrays.asList("list", "page"));
        ACTION_DICT.put("分页", Arrays.asList("page", "list"));
        ACTION_DICT.put("详情", Arrays.asList("detail", "info"));
        ACTION_DICT.put("审核", Arrays.asList("audit", "approve", "review"));
        ACTION_DICT.put("审批", Arrays.asList("approve", "audit", "approval"));
        ACTION_DICT.put("驳回", Arrays.asList("reject"));
        ACTION_DICT.put("提交", Arrays.asList("submit", "commit"));
        ACTION_DICT.put("作废", Arrays.asList("cancel", "invalid", "void"));
        ACTION_DICT.put("取消", Arrays.asList("cancel"));
        ACTION_DICT.put("校验", Arrays.asList("check", "validate", "verify"));
        ACTION_DICT.put("验证", Arrays.asList("validate", "verify", "check"));
        ACTION_DICT.put("检查", Arrays.asList("check", "validate"));
        ACTION_DICT.put("导入", Arrays.asList("import", "upload"));
        ACTION_DICT.put("导出", Arrays.asList("export", "download"));
        ACTION_DICT.put("同步", Arrays.asList("sync", "synchronize"));
        ACTION_DICT.put("计算", Arrays.asList("calculate", "calc", "compute"));
        ACTION_DICT.put("生成", Arrays.asList("generate", "create", "build"));
        ACTION_DICT.put("回调", Arrays.asList("callback", "notify"));
        ACTION_DICT.put("推送", Arrays.asList("push", "send"));
        ACTION_DICT.put("定时", Arrays.asList("job", "task", "schedule"));
    }

    // 识别「有几张表 / 涉及哪些表 / 表名」类问题（排除"报表"）
    private static final Pattern TABLE_QUESTION_PATTERN = Pattern.compile(
            "(?<!报)表名|几张(?:数据库)?表|多少张表|(?:哪些|什么|几个)(?:数据库)?表|(?:数据库|业务|相关|核心)表|涉及.{0,6}表|用到.{0,6}表");

    // 大模型推断检索词的输出行前缀
    private static final Pattern ACTION_LINE_PREFIX = Pattern.compile("^\\s*(?:动作|action)", Pattern.CASE_INSENSITIVE);

    /**
     * 从用户自然语言提问中提炼核心业务关键词
     *
     * @param explicitKeyword 显式指定关键词（可选）
     * @param question        用户提问
     * @return 提炼后的核心关键词
     */
    @Override
    public String resolveCoreKeyword(String explicitKeyword, String question) {
        if (StringUtils.isNotBlank(explicitKeyword)) {
            return explicitKeyword.trim();
        }
        if (StringUtils.isBlank(question)) {
            return "";
        }

        String cleaned = question.trim();
        // 剥离末尾标点
        cleaned = cleaned.replaceAll("[?？!！,，。；;:：\\s]+$", "").trim();
        // 剥离前置无意义动词
        cleaned = PREFIX_PATTERN.matcher(cleaned).replaceFirst("");
        // 剥离后置疑问短语
        cleaned = SUFFIX_PATTERN.matcher(cleaned).replaceFirst("");
        // 剔除标点符号与多余空格
        cleaned = cleaned.replaceAll("[?？!！,，。；;:：\\s]+", "").trim();

        // 若剥离后得到有效核心词，优先采用
        if (StringUtils.isNotBlank(cleaned)) {
            log.info("从提问「{}」提炼核心关键词为:「{}」", question, cleaned);
            return cleaned;
        }
        return question.trim();
    }

    /**
     * 构建多粒度的中文与对应英文搜索 Token 词表
     *
     * @param keyword  核心业务词
     * @param question 完整提问文本
     * @return 关联的搜索 Token 集合
     */
    @Override
    public Set<String> buildSearchTokens(String keyword, String question) {
        Set<String> tokens = new HashSet<>();
        if (StringUtils.isNotBlank(keyword)) {
            String lowerKeyword = keyword.toLowerCase();
            tokens.add(lowerKeyword);

            // 去掉动作词得到业务实体词：例如“实报费用新增” -> “实报费用”，整串在代码中通常不会原样出现
            String entityKeyword = lowerKeyword;
            for (String action : ACTION_DICT.keySet()) {
                entityKeyword = entityKeyword.replace(action, "");
            }
            if (entityKeyword.length() >= Constants.TWO && !entityKeyword.equals(lowerKeyword)) {
                tokens.add(entityKeyword);
            }

            // 词根拆解：例如“调拨单” -> “调拨”；“盘点单” -> “盘点”；“实报费用” -> “实报”
            for (String word : Arrays.asList(lowerKeyword, entityKeyword)) {
                if (word.length() >= Constants.THREE
                        && (word.endsWith("单") || word.endsWith("表") || word.endsWith("项") || word.endsWith("费"))) {
                    tokens.add(word.substring(Constants.ZERO, word.length() - Constants.ONE));
                }
            }
        }

        // 提取提问中的英文单词（如用户直接输入了 allocationOrder 等）
        Matcher enMatcher = Pattern.compile("[a-zA-Z]{3,}").matcher(StringUtils.defaultString(question));
        while (enMatcher.find()) {
            tokens.add(enMatcher.group().toLowerCase());
        }

        // 匹配业务字典扩展关联英文 Token
        for (Map.Entry<String, List<String>> entry : BUSINESS_DICT.entrySet()) {
            String dictKey = entry.getKey();
            if ((StringUtils.isNotBlank(keyword) && keyword.contains(dictKey))
                    || (StringUtils.isNotBlank(question) && question.contains(dictKey))) {
                tokens.add(dictKey.toLowerCase());
                tokens.addAll(entry.getValue());
            }
        }

        log.info("关键词「{}」构建完成的检索 Token 词表: {}", keyword, tokens);
        return tokens;
    }

    /**
     * 按本地动作词典提取提问中的动作词
     *
     * @param question 用户提问
     * @return 英文动作 Token 集合
     */
    @Override
    public Set<String> resolveActionTokens(String question) {
        Set<String> actions = new LinkedHashSet<>();
        if (StringUtils.isBlank(question)) {
            return actions;
        }
        for (Map.Entry<String, List<String>> entry : ACTION_DICT.entrySet()) {
            if (question.contains(entry.getKey())) {
                actions.addAll(entry.getValue());
            }
        }
        return actions;
    }

    /**
     * 判断业务字典或提问本身是否已提供英文 Token
     *
     * @param keyword  核心业务词
     * @param question 完整提问文本
     * @return 是否已有英文 Token
     */
    @Override
    public boolean hasEnglishToken(String keyword, String question) {
        return buildSearchTokens(keyword, question).stream().anyMatch(token -> token.matches("[a-z0-9_]+"));
    }

    /**
     * 判断是否为表结构类问题
     *
     * @param question 用户提问
     * @return 是否为表结构类问题
     */
    @Override
    public boolean isTableQuestion(String question) {
        return StringUtils.isNotBlank(question) && TABLE_QUESTION_PATTERN.matcher(question).find();
    }

    /**
     * 借助大模型推断提问相关的英文检索词，区分业务实体词与动作词
     *
     * @param question 用户提问
     * @return 推断结果，调用失败时返回空集合
     */
    @Override
    public CodeSearchTermsDTO inferSearchTermsWithAI(String question) {
        CodeSearchTermsDTO terms = new CodeSearchTermsDTO();
        if (StringUtils.isBlank(question)) {
            return terms;
        }

        try {
            log.info("调用大模型推断问题「{}」相关的代码检索词...", question);
            List<OpenChatMessageDTO> promptMessages = new ArrayList<>();
            promptMessages.add(OpenChatMessageDTO.builder()
                    .role("system")
                    .content("你是一名资深 Java 全栈开发专家。请根据用户的中文提问，推断在 Spring Boot/MyBatis 业务工程中大概率会用到的英文检索词，"
                            + "严格按两行输出，不要输出任何解释文字：\n"
                            + "实体: 2 到 5 个业务实体的英文类名、表名核心词或缩写（纯小写字母或下划线，英文逗号分隔）\n"
                            + "动作: 0 到 4 个方法名中的动作词（如 add, save, check, validate, audit，英文逗号分隔，问题不涉及动作时留空）\n"
                            + "例如输入“调拨单新增的校验逻辑”，输出：\n实体: allocation,allocation_order,transfer\n动作: add,save,check,validate")
                    .build());
            promptMessages.add(OpenChatMessageDTO.builder()
                    .role("user")
                    .content(question)
                    .build());

            String reply = openCodeService.chat(promptMessages);
            if (StringUtils.isNotBlank(reply)) {
                for (String line : reply.split("\\r?\\n")) {
                    // 未带前缀的行按实体词处理，兼容只输出一行逗号分隔词的回复
                    Set<String> target = ACTION_LINE_PREFIX.matcher(line).find() ? terms.getActionTokens() : terms.getEntityTokens();
                    String body = line.replaceFirst("^[^:：]*[:：]", "");
                    String clean = body.replaceAll("[`'\"\\s]+", "").toLowerCase();
                    for (String part : clean.split("[,，、]")) {
                        if (part.matches("^[a-z_]{2,}$")) {
                            target.add(part);
                        }
                    }
                }
            }
            log.info("大模型推断检索词结果: 实体 {}, 动作 {}", terms.getEntityTokens(), terms.getActionTokens());
        } catch (Exception e) {
            log.warn("大模型推断检索词异常: {}", e.getMessage());
        }

        return terms;
    }

    /**
     * 借助大模型推断相关的英文类名/表名前缀（实体词与动作词合并返回）
     *
     * @param question 用户提问
     * @return 大模型推断的英文 Token 集合
     */
    @Override
    public Set<String> inferEnglishTokensWithAI(String question) {
        CodeSearchTermsDTO terms = inferSearchTermsWithAI(question);
        Set<String> aiTokens = new HashSet<>(terms.getEntityTokens());
        aiTokens.addAll(terms.getActionTokens());
        return aiTokens;
    }
}
