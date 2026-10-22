package org.example.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ct.common.core.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.example.domain.dto.OpenChatMessageDTO;
import org.example.domain.dto.OpenChatToolCallDTO;
import org.example.domain.dto.OpenChatToolDTO;
import org.example.domain.dto.YunxiaoCodeScanParam;
import org.example.domain.dto.YunxiaoCodeScanResultDTO;
import org.example.service.CodeSearchKeywordService;
import org.example.service.OpenCodeService;
import org.example.service.YunxiaoAgentToolService;
import org.example.service.YunxiaoCodeAgentService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 云效代码 Agent 多轮 Tool Calling 深度探查服务实现类
 *
 * @author zeymo
 */
@Slf4j
@Service
public class YunxiaoCodeAgentServiceImpl implements YunxiaoCodeAgentService {

    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String ROLE_TOOL = "tool";

    @Resource
    private OpenCodeService openCodeService;

    @Resource
    private YunxiaoAgentToolService yunxiaoAgentToolService;

    @Resource
    private CodeSearchKeywordService codeSearchKeywordService;

    /**
     * 执行多轮自主 Tool Calling 深度探查与分析
     *
     * @param param          扫描参数
     * @param repoDir        本地克隆仓库目录
     * @param actualRepoName 仓库名
     * @param actualRepoId   仓库 ID
     * @return 深度扫描与分析结果
     */
    @Override
    public YunxiaoCodeScanResultDTO executeDeepScan(YunxiaoCodeScanParam param, File repoDir,
                                                    String actualRepoName, String actualRepoId) {
        String question = param.getQuestion().trim();
        String searchKeyword = codeSearchKeywordService.resolveCoreKeyword(param.getKeyword(), question);
        List<OpenChatToolDTO> tools = yunxiaoAgentToolService.getToolDefinitions();

        log.info("【Agent 启动】开始多轮自主 Tool Calling 深度探查，仓库: [{}], 分支: [{}], 核心词: [{}], 诉求: [{}]",
                actualRepoName, param.getBranch(), searchKeyword, question);

        // 1. 初始化对话上下文
        List<OpenChatMessageDTO> conversation = new ArrayList<>();

        // 构建系统角色提示词
        String systemPrompt = "你是一名直接参与企业级业务研发的资深 Java 核心开发专家与代码深度探查 Agent。"
                + "你拥有本地工程的搜索工具（search_files, grep_code, read_file_range）。"
                + "面对用户的问题，你必须像资深程序员一样进行顺藤摸瓜式的多轮探查：\n"
                + "1. 先用 search_files 模糊搜索匹配的 Entity/Controller/Service/Mapper 文件；\n"
                + "2. 用 read_file_range 仔细研读核心实体表注解（如 @TableName(\"...\")）、主外键、字段；\n"
                + "3. 顺着业务调用链，用 grep_code 查找联动表（如库存流水表 statement、明细表 detail、日志表 log、第三方同步表等）；\n"
                + "4. 探查完毕后，停止调用工具，直接输出一份高密度、清晰分类的表名清单与代码实现深度解析。严禁输出客套套话。";

        conversation.add(OpenChatMessageDTO.builder()
                .role(ROLE_SYSTEM)
                .content(systemPrompt)
                .build());

        // 构建初始用户提问上下文
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("【代码库分支背景】\n")
                .append("- 代码库: ").append(actualRepoName).append(" (ID: ").append(actualRepoId).append(")\n")
                .append("- 分支: ").append(param.getBranch()).append("\n")
                .append("- 提问诉求: ").append(question).append("\n")
                .append("- 初始提炼核心词: ").append(searchKeyword).append("\n\n")
                .append("请充分利用工具探查代码，理清全部涉及的数据库表名（区分核心业务表、联动流水表、日志/同步表）并给出具体代码文件与行号依据。");

        conversation.add(OpenChatMessageDTO.builder()
                .role(ROLE_USER)
                .content(userPrompt.toString())
                .build());

        Set<String> investigatedFiles = new HashSet<>();
        String finalAnalysis = "";
        int turn = Constants.ZERO;
        int maxTurns = Constants.FIVE;

        // 2. 多轮 Tool Calling 交互循环 (最多交互 5 轮)
        while (turn < maxTurns) {
            turn++;
            log.info("Agent 深度探查第 {}/{} 轮交互开始...", turn, maxTurns);

            OpenChatMessageDTO assistantReply;
            try {
                assistantReply = openCodeService.chatWithTools(conversation, tools);
            } catch (Exception e) {
                log.error("第 {} 轮大模型调用异常: {}", turn, e.getMessage(), e);
                break;
            }

            if (assistantReply == null) {
                log.warn("第 {} 轮大模型返回空，中断循环", turn);
                break;
            }

            // 将大模型的回复放入上下文
            conversation.add(assistantReply);

            // 检查大模型是否发起了工具调用
            List<OpenChatToolCallDTO> toolCalls = assistantReply.getToolCalls();
            if (CollectionUtils.isEmpty(toolCalls)) {
                // 没有发起工具调用，说明大模型已经得出综合分析结论
                log.info("Agent 在第 {} 轮完成探查并生成最终分析报告，长度: {}", turn,
                        assistantReply.getContent() != null ? assistantReply.getContent().length() : Constants.ZERO);
                finalAnalysis = assistantReply.getContent();
                break;
            }

            // 执行大模型请求调用的各个工具
            for (OpenChatToolCallDTO toolCall : toolCalls) {
                String toolName = toolCall.getFunction() != null ? toolCall.getFunction().getName() : "";
                String arguments = toolCall.getFunction() != null ? toolCall.getFunction().getArguments() : "{}";
                log.info("Agent 发起工具调用: [{}] 入参: {}", toolName, arguments);

                String toolResult = yunxiaoAgentToolService.executeTool(toolName, arguments, repoDir);
                log.info("工具 [{}] 执行完毕，返回结果长度: {}", toolName, toolResult.length());

                // 记录探查到的文件
                if (arguments.contains("file_path")) {
                    try {
                        JSONObject json = JSON.parseObject(arguments);
                        String path = json.getString("file_path");
                        if (StringUtils.isNotBlank(path)) {
                            investigatedFiles.add(path);
                        }
                    } catch (Exception ignored) {
                    }
                }

                // 将工具执行结果作为 tool 角色消息追加到上下文
                conversation.add(OpenChatMessageDTO.builder()
                        .role(ROLE_TOOL)
                        .name(toolName)
                        .toolCallId(toolCall.getId())
                        .content(toolResult)
                        .build());
            }
        }

        // 3. 若达到最大轮数仍未返回最终文本，进行一次终结总结提问
        if (StringUtils.isBlank(finalAnalysis)) {
            log.info("Agent 达到最大探查轮数或未输出最终结论，进行一次性汇总归纳调用...");
            conversation.add(OpenChatMessageDTO.builder()
                    .role(ROLE_USER)
                    .content("请根据上面探查到的所有源码事实和线索，立即输出最终总结分析报告，详细列明涉及的全部表名及具体代码实现。")
                    .build());
            try {
                finalAnalysis = openCodeService.chat(conversation);
            } catch (Exception e) {
                log.error("最终汇总大模型调用异常: {}", e.getMessage(), e);
                finalAnalysis = "多轮探查已完成，但大模型生成总结时超时或异常: " + e.getMessage();
            }
        }

        List<String> fileList = new ArrayList<>(investigatedFiles);

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
}
