package org.example.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ct.common.core.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.example.domain.dto.CodeAgentSeedDTO;
import org.example.domain.dto.OpenChatMessageDTO;
import org.example.domain.dto.OpenChatToolCallDTO;
import org.example.domain.dto.OpenChatToolDTO;
import org.example.domain.dto.YunxiaoCodeScanParam;
import org.example.domain.dto.YunxiaoCodeScanResultDTO;
import org.example.service.CodeSearchKeywordService;
import org.example.service.OpenCodeService;
import org.example.service.YunxiaoAgentToolService;
import org.example.service.YunxiaoCodeAgentService;
import org.example.service.YunxiaoGitCloneService;
import org.example.util.CodeCitationVerifier;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 云效代码 Agent 多轮 Tool Calling 深度探查服务实现类
 *
 * @author zeymo
 */
@Slf4j
@Service
public class YunxiaoCodeAgentServiceImpl implements YunxiaoCodeAgentService, DisposableBean {

    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String ROLE_TOOL = "tool";

    /**
     * 工具执行失败时的返回前缀（见 YunxiaoAgentToolServiceImpl）
     */
    private static final List<String> TOOL_ERROR_PREFIXES = List.of("错误", "未知工具名称", "工具执行异常");

    /**
     * 最多交互轮数
     */
    private static final int MAX_TURNS = Constants.TEN;

    /**
     * 单个工具结果写入上下文的最大字符数
     */
    private static final int MAX_TOOL_RESULT_CHARS = Constants.TEN_THOUSAND;

    /**
     * 保留完整工具结果的最近轮数，更早的结果压缩为一行摘要
     */
    private static final int KEEP_RECENT_TURNS = Constants.THREE;

    /**
     * 剩余时间低于该值时提示 Agent 本轮为最后一轮
     */
    private static final long LAST_TURN_THRESHOLD_MILLIS = Constants.SIXTY_THOUSAND;

    /**
     * 初始线索中列出的相关文件数 / 方法数
     */
    private static final int SEED_TOP_FILES = Constants.FIVE;
    private static final int SEED_MAX_METHODS = Constants.SIXTY;

    /**
     * 工具输出中的源码文件路径，如 ct-modules/.../XxxServiceImpl.java:120
     */
    private static final Pattern TOOL_OUTPUT_PATH = Pattern.compile("([\\w./-]+/[\\w$-]+\\.(?:java|xml))");

    /**
     * 多轮探查循环的总时限（不含最终汇总）
     */
    private long loopTimeoutMillis = (long) Constants.FOUR * Constants.SIXTY_THOUSAND;

    /**
     * 最终汇总调用的时限
     */
    private long summaryTimeoutMillis = (long) Constants.NINE * Constants.TEN_THOUSAND;

    /**
     * 本地检索初始线索的时限（可能包含一次大模型推断检索词）
     */
    private long seedTimeoutMillis = (long) Constants.THIRTY * Constants.THOUSAND;

    /**
     * 大模型调用线程池：用于对单次调用施加剩余时限，超时后主流程直接进入汇总
     */
    private final ExecutorService llmExecutor = Executors.newCachedThreadPool(new DaemonThreadFactory());

    @Resource
    private OpenCodeService openCodeService;

    @Resource
    private YunxiaoAgentToolService yunxiaoAgentToolService;

    @Resource
    private CodeSearchKeywordService codeSearchKeywordService;

    @Resource
    private YunxiaoGitCloneService yunxiaoGitCloneService;

    /**
     * 执行多轮自主 Tool Calling 深度探查与分析
     *
     * @param param          扫描参数
     * @param repoDir        本地克隆仓库目录
     * @param actualRepoName 仓库名
     * @param actualRepoId   仓库 ID
     * @return 深度扫描与分析结果
     * @throws IllegalStateException 未成功执行任何工具调用（报告缺乏源码依据）时抛出，由调用方降级为普通扫描
     */
    @Override
    public YunxiaoCodeScanResultDTO executeDeepScan(YunxiaoCodeScanParam param, File repoDir,
                                                    String actualRepoName, String actualRepoId) {
        long startedAt = System.currentTimeMillis();
        long loopDeadline = startedAt + loopTimeoutMillis;
        String question = param.getQuestion().trim();
        String searchKeyword = codeSearchKeywordService.resolveCoreKeyword(param.getKeyword(), question);
        boolean tableQuestion = codeSearchKeywordService.isTableQuestion(question);
        List<OpenChatToolDTO> tools = yunxiaoAgentToolService.getToolDefinitions();

        log.info("【Agent 启动】开始多轮自主 Tool Calling 深度探查，仓库: [{}], 分支: [{}], 核心词: [{}], 表结构类问题: [{}], 诉求: [{}]",
                actualRepoName, param.getBranch(), searchKeyword, tableQuestion, question);

        // 1. 本地预检索：给 Agent 英文检索词、相关文件与方法列表，避免前几轮浪费在试关键词上
        CodeAgentSeedDTO seed = buildSeed(repoDir, searchKeyword, question, loopDeadline);

        List<OpenChatMessageDTO> conversation = new ArrayList<>();
        conversation.add(OpenChatMessageDTO.builder()
                .role(ROLE_SYSTEM)
                .content(tableQuestion ? buildTableSystemPrompt() : buildLogicSystemPrompt())
                .build());
        conversation.add(OpenChatMessageDTO.builder()
                .role(ROLE_USER)
                .content(buildUserPrompt(actualRepoName, actualRepoId, param.getBranch(), question, searchKeyword, seed, tableQuestion))
                .build());

        AgentState state = new AgentState(repoDir.toPath().toAbsolutePath().normalize());
        String finalAnalysis = "";
        boolean nudged = false;
        int turn = Constants.ZERO;

        // 2. 多轮 Tool Calling 交互循环（最多 MAX_TURNS 轮，且不超过总时限）
        while (turn < MAX_TURNS) {
            long remaining = loopDeadline - System.currentTimeMillis();
            if (remaining <= Constants.ZERO) {
                log.warn("Agent 探查达到总时限 {} ms，停止探查进入汇总", loopTimeoutMillis);
                break;
            }
            turn++;
            compressOldToolResults(state, turn);
            log.info("Agent 深度探查第 {}/{} 轮交互开始，剩余时间 {} 秒...", turn, MAX_TURNS, remaining / Constants.THOUSAND);

            OpenChatMessageDTO assistantReply;
            List<OpenChatMessageDTO> snapshot = new ArrayList<>(conversation);
            try {
                assistantReply = callWithTimeout(() -> openCodeService.chatWithTools(snapshot, tools), remaining);
            } catch (TimeoutException e) {
                log.warn("第 {} 轮大模型调用超出剩余时限，停止探查进入汇总", turn);
                break;
            } catch (Exception e) {
                log.error("第 {} 轮大模型调用异常: {}", turn, e.getMessage(), e);
                break;
            }

            if (assistantReply == null) {
                log.warn("第 {} 轮大模型返回空，中断循环", turn);
                break;
            }
            conversation.add(assistantReply);

            List<OpenChatToolCallDTO> toolCalls = assistantReply.getToolCalls();
            if (CollectionUtils.isEmpty(toolCalls)) {
                // 尚未成功读取任何源码就给出结论：要求先用工具核实一次，避免凭空作答
                if (state.successfulCalls.get() == Constants.ZERO && !nudged) {
                    nudged = true;
                    log.info("Agent 第 {} 轮未调用任何工具即给出结论，要求先核实源码", turn);
                    conversation.add(OpenChatMessageDTO.builder()
                            .role(ROLE_USER)
                            .content("你还没有读取任何源码。请先调用工具（如 read_method、list_methods）核实代码，再给出结论。")
                            .build());
                    continue;
                }
                log.info("Agent 在第 {} 轮完成探查并生成最终分析报告，长度: {}", turn, StringUtils.length(assistantReply.getContent()));
                finalAnalysis = assistantReply.getContent();
                break;
            }

            String progressHint = buildProgressHint(turn, loopDeadline);
            for (int i = Constants.ZERO; i < toolCalls.size(); i++) {
                // 进度提示附在本轮最后一个工具结果上
                conversation.add(executeToolCall(toolCalls.get(i), state, turn, i == toolCalls.size() - Constants.ONE ? progressHint : null));
            }
        }

        // 3. 没有成功读取任何源码时不做汇总（只能凭空作答），交由调用方降级为普通扫描
        if (state.successfulCalls.get() == Constants.ZERO) {
            throw new IllegalStateException("Agent 未能成功调用任何代码探查工具");
        }

        // 4. 达到最大轮数或时限仍未返回最终文本，进行一次终结总结提问
        if (StringUtils.isBlank(finalAnalysis)) {
            finalAnalysis = summarize(conversation);
        }

        List<String> fileList = new ArrayList<>(state.readFiles);
        state.referencedFiles.stream().filter(path -> !state.readFiles.contains(path)).forEach(fileList::add);
        finalAnalysis = CodeCitationVerifier.appendWarnings(finalAnalysis, repoDir, fileList);

        log.info("【Agent 结束】共 {} 轮、成功工具调用 {} 次、读取文件 {} 个，耗时 {} 秒", turn, state.successfulCalls.get(),
                state.readFiles.size(), (System.currentTimeMillis() - startedAt) / Constants.THOUSAND);

        return YunxiaoCodeScanResultDTO.builder()
                .repositoryId(actualRepoId)
                .repositoryName(actualRepoName)
                .branch(param.getBranch())
                .question(question)
                .matchedFileCount(fileList.size())
                .matchedFiles(fileList)
                .analysisReport(finalAnalysis)
                .build();
    }

    /**
     * 本地预检索初始线索，失败或超时时返回 null（Agent 仍可自行探查）
     */
    private CodeAgentSeedDTO buildSeed(File repoDir, String searchKeyword, String question, long loopDeadline) {
        long timeout = Math.min(seedTimeoutMillis, loopDeadline - System.currentTimeMillis());
        try {
            return callWithTimeout(() -> yunxiaoGitCloneService.buildAgentSeed(repoDir, searchKeyword, question, SEED_TOP_FILES), timeout);
        } catch (Exception e) {
            log.warn("深度扫描本地预检索失败，Agent 将从零开始探查: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 表结构类问题的系统提示词
     */
    private String buildTableSystemPrompt() {
        return "你是一名直接参与企业级业务研发的资深 Java 核心开发专家与代码深度探查 Agent。"
                + "你拥有本地工程的探查工具（find_table_usages, list_methods, read_method, find_callers, get_mapper_sql, search_files, grep_code, read_file_range）。"
                + "面对用户的问题，你必须像资深程序员一样进行顺藤摸瓜式的多轮探查：\n"
                + "1. 先用 find_table_usages 传入英文业务关键词（优先使用线索中的英文检索词），一次拿到相关表名、实体类及引用位置的全景；\n"
                + "2. 对线索中的核心 Service 用 list_methods 列出方法，再用 read_method 读取新增、审核、出入库等关键方法，顺着调用链逐层下钻；\n"
                + "3. 遇到 Mapper 自定义方法时，用 get_mapper_sql 查看 XML 中的完整 SQL，找出联表、子查询中的隐藏表；\n"
                + "4. 用 grep_code 补充查找联动表（如库存流水表 statement、明细表 detail、日志表 log、第三方同步表等）；"
                + "read_file_range 仅用于查看字段定义等非方法片段；\n"
                + "5. 每轮可同时发起多个工具调用以提高效率；信息足够后立即停止调用工具，"
                + "直接输出一份高密度、清晰分类的表名清单（区分核心业务表、联动流水表、日志/同步表），每张表给出「文件名.java:行号」依据。\n"
                + "引用的行号必须取自工具输出，不得估算；严禁输出客套套话。";
    }

    /**
     * 逻辑类问题（校验、流程、计算等）的系统提示词
     */
    private String buildLogicSystemPrompt() {
        return "你是一名直接参与企业级业务研发的资深 Java 核心开发专家与代码深度探查 Agent。"
                + "你拥有本地工程的探查工具（list_methods, read_method, find_callers, get_mapper_sql, find_table_usages, search_files, grep_code, read_file_range）。"
                + "面对用户的问题，你必须像资深程序员一样沿调用链进行多轮探查：\n"
                + "1. 优先用 read_method 读取线索中已列出的核心方法；其他文件先用 list_methods 列出方法，再按方法名读取，不要猜行号；\n"
                + "2. 顺着方法体内的调用逐层下钻到校验、计算、落库等私有方法和下游 Service；\n"
                + "3. 需要确认触发场景时，用 find_callers 向上查找调用方（可传 class_name 缩小范围）；\n"
                + "4. 遇到 Mapper 自定义方法时，用 get_mapper_sql 查看 SQL 条件；search_files / grep_code 只在线索不足时使用，read_file_range 仅用于查看字段、常量、枚举定义；\n"
                + "5. 每轮可同时发起多个工具调用以提高效率；信息足够后立即停止调用工具，直接输出最终结论。\n"
                + "【输出要求】\n"
                + "- 第一句直奔主题：核心逻辑在哪个类的哪个方法（文件名.java:行号），分几步；\n"
                + "- 按业务执行流分点拆解，写清方法名、字段名、判定条件、状态值、报错文案、SQL 条件；触发入口只用一行列出；\n"
                + "- 「可留意的点」只写源码中能直接看出的业务特例或坑点，最多 3 条，没有就不写；\n"
                + "- 若问题只是询问简单事实（如有哪些状态、字段、枚举值），直接用列表或表格回答；\n"
                + "- 代码位置统一写成「文件名.java:行号」，行号必须取自工具输出，不得估算；没有读到的类、方法、字段一律不写；\n"
                + "- 严禁寒暄、前言或总结后记。";
    }

    /**
     * 构建初始用户提问上下文，附带本地预检索线索
     */
    private String buildUserPrompt(String repoName, String repoId, String branch, String question, String searchKeyword,
                                   CodeAgentSeedDTO seed, boolean tableQuestion) {
        StringBuilder sb = new StringBuilder();
        sb.append("【代码库分支背景】\n")
                .append("- 代码库: ").append(repoName).append(" (ID: ").append(repoId).append(")\n")
                .append("- 分支: ").append(branch).append("\n")
                .append("- 提问诉求: ").append(question).append("\n")
                .append("- 初始提炼核心词: ").append(searchKeyword).append("\n\n");

        if (seed != null && !seed.getTopFiles().isEmpty()) {
            sb.append("【本地预检索线索（按相关度排序，可能不完整，请以工具读取的源码为准）】\n");
            if (!seed.getEnglishTokens().isEmpty()) {
                sb.append("- 英文检索词: ").append(String.join(", ", seed.getEnglishTokens())).append("\n");
            }
            if (!seed.getActionTokens().isEmpty()) {
                sb.append("- 方法名动作词: ").append(String.join(", ", seed.getActionTokens())).append("\n");
            }
            sb.append("- 相关文件:\n");
            seed.getTopFiles().forEach(path -> sb.append("  - ").append(path).append("\n"));
            if (StringUtils.isNotBlank(seed.getTopFilePath()) && !seed.getTopFileMethods().isEmpty()) {
                sb.append("- ").append(seed.getTopFilePath()).append(" 的方法列表:\n");
                seed.getTopFileMethods().stream().limit(SEED_MAX_METHODS).forEach(m -> sb.append("  - ").append(m).append("\n"));
                if (seed.getTopFileMethods().size() > SEED_MAX_METHODS) {
                    sb.append("  - ……共 ").append(seed.getTopFileMethods().size()).append(" 个方法，其余可用 list_methods 查看\n");
                }
            }
            sb.append("\n");
        }

        sb.append(tableQuestion
                ? "请充分利用工具探查代码，理清全部涉及的数据库表名（区分核心业务表、联动流水表、日志/同步表）并给出具体代码文件与行号依据。"
                : "请从线索中的核心方法开始读取源码，沿调用链理清完整业务逻辑，并给出具体代码文件与行号依据。");
        return sb.toString();
    }

    /**
     * 执行单个工具调用并构建 tool 消息：相同工具 + 相同参数直接复用缓存；结果超长时截断；记录涉及的文件
     *
     * @param toolCall     大模型发起的工具调用
     * @param state        探查状态
     * @param turn         当前轮次
     * @param progressHint 附加的进度提示（可为 null）
     * @return tool 角色消息
     */
    private OpenChatMessageDTO executeToolCall(OpenChatToolCallDTO toolCall, AgentState state, int turn, String progressHint) {
        String toolName = toolCall.getFunction() != null ? toolCall.getFunction().getName() : "";
        String arguments = toolCall.getFunction() != null ? StringUtils.defaultIfBlank(toolCall.getFunction().getArguments(), "{}") : "{}";
        String cacheKey = toolName + "|" + normalizeArguments(arguments);

        String content;
        ToolRecord cached = state.cache.get(cacheKey);
        if (cached != null && !cached.compressed) {
            // 原结果仍完整保留在上下文中，只需指向它
            log.info("Agent 重复调用工具: [{}] 入参: {}，复用第 {} 轮结果", toolName, arguments, cached.turn);
            content = "（与第 " + cached.turn + " 轮的同参数调用结果相同，请直接参考该结果，不要重复调用）";
        } else {
            String fullResult;
            if (cached != null) {
                log.info("Agent 重复调用工具: [{}] 入参: {}，原结果已压缩，重新提供缓存结果", toolName, arguments);
                fullResult = cached.fullResult;
            } else {
                log.info("Agent 发起工具调用: [{}] 入参: {}", toolName, arguments);
                String toolResult = StringUtils.defaultString(yunxiaoAgentToolService.executeTool(toolName, arguments, state.repoRoot.toFile()));
                log.info("工具 [{}] 执行完毕，返回结果长度: {}", toolName, toolResult.length());
                if (TOOL_ERROR_PREFIXES.stream().noneMatch(toolResult::startsWith)) {
                    state.successfulCalls.incrementAndGet();
                    collectFiles(state, arguments, toolResult);
                }
                fullResult = capLength(toolResult);
            }
            content = fullResult;
            OpenChatMessageDTO message = buildToolMessage(toolCall, toolName, withHint(content, progressHint));
            ToolRecord record = new ToolRecord(message, toolName, arguments, fullResult, turn);
            state.records.add(record);
            state.cache.put(cacheKey, record);
            return message;
        }
        return buildToolMessage(toolCall, toolName, withHint(content, progressHint));
    }

    private OpenChatMessageDTO buildToolMessage(OpenChatToolCallDTO toolCall, String toolName, String content) {
        return OpenChatMessageDTO.builder()
                .role(ROLE_TOOL)
                .name(toolName)
                .toolCallId(toolCall.getId())
                .content(content)
                .build();
    }

    private String withHint(String content, String progressHint) {
        return progressHint == null ? content : content + "\n\n" + progressHint;
    }

    /**
     * 将较早轮次的完整工具结果压缩为一行摘要，控制上下文长度
     */
    private void compressOldToolResults(AgentState state, int currentTurn) {
        int compressedCount = Constants.ZERO;
        for (ToolRecord record : state.records) {
            if (!record.compressed && currentTurn - record.turn > KEEP_RECENT_TURNS) {
                String firstLine = StringUtils.abbreviate(record.fullResult.split("\\r?\\n", Constants.TWO)[Constants.ZERO].trim(), Constants.HUNDRED);
                record.message.setContent("[第 " + record.turn + " 轮结果已压缩] " + record.toolName + " "
                        + StringUtils.abbreviate(record.arguments, Constants.TWO_HUNDRED) + " -> " + firstLine
                        + "（原结果 " + record.fullResult.length() + " 字符，如仍需要可用相同参数重新调用）");
                record.compressed = true;
                compressedCount++;
            }
        }
        if (compressedCount > Constants.ZERO) {
            log.info("第 {} 轮开始前压缩早期工具结果 {} 条", currentTurn, compressedCount);
        }
    }

    /**
     * 进度提示：剩余轮数与时间；最后一轮要求直接输出结论
     */
    private String buildProgressHint(int turn, long loopDeadline) {
        int remainingTurns = MAX_TURNS - turn;
        long remainingMillis = loopDeadline - System.currentTimeMillis();
        if (remainingTurns <= Constants.ZERO || remainingMillis < LAST_TURN_THRESHOLD_MILLIS) {
            return "【进度】探查轮数或时间即将用尽，下一轮请停止调用工具，直接根据已读取的源码输出最终结论。";
        }
        return "【进度】已完成 " + turn + "/" + MAX_TURNS + " 轮，剩余约 " + remainingMillis / Constants.THOUSAND
                + " 秒；信息足够时请立即停止调用工具并输出结论。";
    }

    /**
     * 最终汇总：基于已探查到的源码输出报告，单独施加时限
     */
    private String summarize(List<OpenChatMessageDTO> conversation) {
        log.info("Agent 达到最大探查轮数或时限，进行一次性汇总归纳调用...");
        conversation.add(OpenChatMessageDTO.builder()
                .role(ROLE_USER)
                .content("请停止调用工具，根据上面探查到的所有源码事实和线索，立即输出最终总结分析报告。"
                        + "只写工具输出中出现过的类、方法、字段与行号，没有读到的内容不要推测。")
                .build());
        List<OpenChatMessageDTO> snapshot = new ArrayList<>(conversation);
        try {
            return callWithTimeout(() -> openCodeService.chat(snapshot), summaryTimeoutMillis);
        } catch (TimeoutException e) {
            log.error("最终汇总大模型调用超时 ({} ms)", summaryTimeoutMillis);
            return "多轮探查已完成，但大模型生成总结时超时，请稍后重试或改用普通代码扫描。";
        } catch (Exception e) {
            log.error("最终汇总大模型调用异常: {}", e.getMessage(), e);
            return "多轮探查已完成，但大模型生成总结时超时或异常: " + e.getMessage();
        }
    }

    /**
     * 在独立线程中执行调用并施加时限；超时后取消任务（底层 HTTP 读超时仍会兜底释放线程）
     */
    private <T> T callWithTimeout(Callable<T> task, long timeoutMillis) throws Exception {
        if (timeoutMillis <= Constants.ZERO) {
            throw new TimeoutException("剩余时间不足");
        }
        Future<T> future = llmExecutor.submit(task);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : e;
        }
    }

    /**
     * 规范化工具入参（按 key 排序、去空白），用于识别重复调用
     */
    private String normalizeArguments(String arguments) {
        try {
            JSONObject json = JSON.parseObject(arguments);
            if (json == null) {
                return "{}";
            }
            Map<String, Object> sorted = new TreeMap<>();
            json.forEach((key, value) -> sorted.put(key, value instanceof String ? ((String) value).trim() : value));
            return JSON.toJSONString(sorted);
        } catch (Exception e) {
            return arguments.trim();
        }
    }

    /**
     * 截断超长工具结果，并提示缩小查询范围
     */
    private String capLength(String result) {
        if (result.length() <= MAX_TOOL_RESULT_CHARS) {
            return result;
        }
        return result.substring(Constants.ZERO, MAX_TOOL_RESULT_CHARS)
                + "\n...（结果过长已截断，省略 " + (result.length() - MAX_TOOL_RESULT_CHARS)
                + " 字符；请用 read_method 按方法读取，或使用更精确的关键词）";
    }

    /**
     * 记录探查涉及的文件：入参中的 file_path 视为已读取，工具输出中出现的仓库内文件视为已引用
     */
    private void collectFiles(AgentState state, String arguments, String toolResult) {
        try {
            JSONObject json = JSON.parseObject(arguments);
            String path = json == null ? null : json.getString("file_path");
            String normalized = normalizeRepoPath(state.repoRoot, path);
            if (normalized != null) {
                state.readFiles.add(normalized);
            }
        } catch (Exception ignored) {
            // 入参不是合法 JSON 时工具本身已返回错误，这里无需处理
        }
        Matcher matcher = TOOL_OUTPUT_PATH.matcher(toolResult);
        while (matcher.find()) {
            String normalized = normalizeRepoPath(state.repoRoot, matcher.group(Constants.ONE));
            if (normalized != null) {
                state.referencedFiles.add(normalized);
            }
        }
    }

    /**
     * 将路径规范化为仓库相对路径，仓库内不存在的文件返回 null
     */
    private String normalizeRepoPath(Path root, String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        Path resolved = root.resolve(StringUtils.stripStart(path.trim().replace("\\", "/"), "/")).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            return null;
        }
        return root.relativize(resolved).toString().replace("\\", "/");
    }

    @Override
    public void destroy() {
        llmExecutor.shutdownNow();
    }

    /**
     * 单次深度扫描的探查状态
     */
    private static final class AgentState {

        private final Path repoRoot;
        private final List<ToolRecord> records = new ArrayList<>();
        private final Map<String, ToolRecord> cache = new HashMap<>();
        private final Set<String> readFiles = new LinkedHashSet<>();
        private final Set<String> referencedFiles = new LinkedHashSet<>();
        private final AtomicInteger successfulCalls = new AtomicInteger();

        private AgentState(Path repoRoot) {
            this.repoRoot = repoRoot;
        }
    }

    /**
     * 写入上下文的工具结果
     */
    private static final class ToolRecord {

        private final OpenChatMessageDTO message;
        private final String toolName;
        private final String arguments;
        /**
         * 截断后的完整结果（压缩后仍保留，用于重复调用时重新提供）
         */
        private final String fullResult;
        private final int turn;
        private boolean compressed;

        private ToolRecord(OpenChatMessageDTO message, String toolName, String arguments, String fullResult, int turn) {
            this.message = message;
            this.toolName = toolName;
            this.arguments = arguments;
            this.fullResult = fullResult;
            this.turn = turn;
        }
    }

    /**
     * 大模型调用线程：守护线程，不阻止 JVM 退出
     */
    private static final class DaemonThreadFactory implements java.util.concurrent.ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "code-agent-llm-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
