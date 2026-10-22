package org.example.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ct.common.core.constant.Constants;
import org.example.config.YunxiaoProperties;
import org.example.domain.dto.YunxiaoCodeScanInstructionDTO;
import org.example.domain.dto.YunxiaoCodeScanParam;
import org.example.domain.dto.YunxiaoCodeScanResultDTO;
import org.example.domain.dto.YunxiaoCodeSnippetDTO;
import org.example.domain.dto.YunxiaoRepositoryDTO;
import org.example.service.impl.CodeSearchKeywordServiceImpl;
import org.example.service.impl.YunxiaoCodeScanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 云效分支代码扫描与大模型分析单元测试
 *
 * @author zeymo
 */
@ExtendWith(MockitoExtension.class)
public class YunxiaoCodeScanServiceTest {

    @InjectMocks
    private YunxiaoCodeScanServiceImpl yunxiaoCodeScanService;

    @Mock
    private RestTemplate yunxiaoRestTemplate;

    @Mock
    private YunxiaoDevopsService yunxiaoDevopsService;

    @Mock
    private OpenCodeService openCodeService;

    @Mock
    private YunxiaoGitCloneService yunxiaoGitCloneService;

    @Mock
    private YunxiaoCodeAgentService yunxiaoCodeAgentService;

    private YunxiaoProperties yunxiaoProperties;

    @BeforeEach
    public void setup() {
        yunxiaoProperties = new YunxiaoProperties();
        yunxiaoProperties.setEndpoint("openapi-rdc.aliyuncs.com");
        yunxiaoProperties.setAccessToken("test-token");
        yunxiaoProperties.setOrganizationId("test-org");

        ReflectionTestUtils.setField(yunxiaoCodeScanService, "yunxiaoProperties", yunxiaoProperties);
        ReflectionTestUtils.setField(yunxiaoCodeScanService, "yunxiaoRestTemplate", yunxiaoRestTemplate);
        ReflectionTestUtils.setField(yunxiaoCodeScanService, "yunxiaoDevopsService", yunxiaoDevopsService);
        ReflectionTestUtils.setField(yunxiaoCodeScanService, "openCodeService", openCodeService);
        ReflectionTestUtils.setField(yunxiaoCodeScanService, "yunxiaoGitCloneService", yunxiaoGitCloneService);
        ReflectionTestUtils.setField(yunxiaoCodeScanService, "codeSearchKeywordService", new CodeSearchKeywordServiceImpl());
        ReflectionTestUtils.setField(yunxiaoCodeScanService, "yunxiaoCodeAgentService", yunxiaoCodeAgentService);
    }

    /**
     * 测试扫描分支代码并进行大模型梳理
     */
    @Test
    public void testScanAndAnalyzeBranch() {
        // 1. Mock 仓库查询
        YunxiaoRepositoryDTO mockRepo = YunxiaoRepositoryDTO.builder()
                .id(6120951L)
                .name("changtai-cloud")
                .path("changtai-cloud")
                .build();
        when(yunxiaoDevopsService.listRepositories(any())).thenReturn(Collections.singletonList(mockRepo));

        // 2. Mock 文件树响应
        JSONArray treeArray = new JSONArray();
        JSONObject fileNode = new JSONObject();
        fileNode.put("id", "blob001");
        fileNode.put("name", "ScmActualReimbursementController.java");
        fileNode.put("path", "ct-modules/ct-modules-scm/src/main/java/com/ct/scm/controller/ScmActualReimbursementController.java");
        fileNode.put("type", "blob");
        fileNode.put("mode", "100644");
        treeArray.add(fileNode);

        when(yunxiaoRestTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(new ResponseEntity<>(treeArray.toJSONString(), HttpStatus.OK));

        // 3. Mock 单文件内容响应
        JSONObject blobObj = new JSONObject();
        blobObj.put("fileName", "ScmActualReimbursementController.java");
        blobObj.put("filePath", fileNode.getString("path"));
        String fakeCode = "public class ScmActualReimbursementController { // 实报业务接口 }";
        blobObj.put("content", Base64.getEncoder().encodeToString(fakeCode.getBytes(StandardCharsets.UTF_8)));

        when(yunxiaoRestTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(new ResponseEntity<>(blobObj.toJSONString(), HttpStatus.OK));

        // 4. Mock 大模型回复
        when(openCodeService.chat(anyList())).thenReturn("### 实报业务梳理报告\n1. 涉及 ScmActualReimbursementController");

        YunxiaoCodeScanParam param = YunxiaoCodeScanParam.builder()
                .repositoryId("changtai-cloud")
                .branch("dev")
                .question("梳理实报使用的地方")
                .build();

        YunxiaoCodeScanResultDTO result = yunxiaoCodeScanService.scanAndAnalyzeBranch(param);

        assertNotNull(result);
        assertEquals("changtai-cloud", result.getRepositoryName());
        assertEquals("dev", result.getBranch());
        assertEquals(Constants.ONE, result.getMatchedFileCount());
        assertTrue(result.getAnalysisReport().contains("ScmActualReimbursementController"));
    }

    /**
     * 测试方案 A：本地 Git 浅克隆缓存模式（保留 2 小时，全量毫秒级扫描）
     */
    @Test
    public void testScanAndAnalyzeBranchWithPlanALocalClone() {
        YunxiaoRepositoryDTO mockRepo = YunxiaoRepositoryDTO.builder()
                .id(6120951L)
                .name("changtai-cloud")
                .path("changtai-cloud")
                .httpUrlToRepo("https://codeup.aliyun.com/6925527fccf3499544d9b1b1/it/changtai-cloud.git")
                .build();
        when(yunxiaoDevopsService.listRepositories(any())).thenReturn(Collections.singletonList(mockRepo));

        YunxiaoCodeSnippetDTO mockSnippet = YunxiaoCodeSnippetDTO.builder()
                .filePath("ct-modules/ct-modules-scm/src/main/java/com/ct/scm/controller/ScmActualReimbursementController.java")
                .snippet("L10: public class ScmActualReimbursementController { // 实报业务接口 }")
                .matchReason("本地全量代码扫描精准命中")
                .build();

        when(yunxiaoGitCloneService.prepareLocalRepository(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new File("."));
        when(yunxiaoGitCloneService.scanLocalRepository(any(File.class), anyString(), anyInt()))
                .thenReturn(Collections.singletonList(mockSnippet));
        when(openCodeService.chat(anyList())).thenReturn("### 方案A本地扫描大模型审计报告\n1. 精准梳理实报业务逻辑");

        YunxiaoCodeScanParam param = YunxiaoCodeScanParam.builder()
                .repositoryId("changtai-cloud")
                .branch("dev")
                .question("梳理实报使用的地方")
                .build();

        YunxiaoCodeScanResultDTO result = yunxiaoCodeScanService.scanAndAnalyzeBranch(param);

        assertNotNull(result);
        assertEquals("changtai-cloud", result.getRepositoryName());
        assertEquals("dev", result.getBranch());
        assertEquals(Constants.ONE, result.getMatchedFileCount());
        assertTrue(result.getAnalysisReport().contains("方案A本地扫描大模型审计报告"));
        verify(yunxiaoGitCloneService).scanLocalRepository(any(File.class), anyString(), eq(Constants.FIVE_HUNDRED));
    }

    /**
     * 测试群聊指令解析与返回
     */
    @Test
    public void testScanAndAnalyzeForChat() {
        YunxiaoRepositoryDTO mockRepo = YunxiaoRepositoryDTO.builder()
                .id(6120951L)
                .name("changtai-cloud")
                .build();
        when(yunxiaoDevopsService.listRepositories(any())).thenReturn(Collections.singletonList(mockRepo));

        JSONArray treeArray = new JSONArray();
        JSONObject fileNode = new JSONObject();
        fileNode.put("id", "blob001");
        fileNode.put("name", "ActualReportService.java");
        fileNode.put("path", "ct-modules/service/ActualReportService.java");
        fileNode.put("type", "blob");
        treeArray.add(fileNode);

        when(yunxiaoRestTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(new ResponseEntity<>(treeArray.toJSONString(), HttpStatus.OK));

        JSONObject blobObj = new JSONObject();
        blobObj.put("fileName", "ActualReportService.java");
        blobObj.put("filePath", fileNode.getString("path"));
        blobObj.put("content", Base64.getEncoder().encodeToString("class ActualReportService {}".getBytes(StandardCharsets.UTF_8)));

        when(yunxiaoRestTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(new ResponseEntity<>(blobObj.toJSONString(), HttpStatus.OK));

        when(openCodeService.chat(anyList())).thenReturn("实报费用新增入口是 ScmActualReimbursementController.addReimbursement");

        String chatReply = yunxiaoCodeScanService.scanAndAnalyzeForChat("扫描项目 changtai-cloud 分支 dev 梳理实报使用的地方");

        assertNotNull(chatReply);
        assertEquals("实报费用新增入口是 ScmActualReimbursementController.addReimbursement", chatReply);
    }

    /**
     * 测试各种缺省项目与缺省分支的指令解析逻辑
     */
    @Test
    public void testParseInstructionVariousFormats() {
        // 1. 完全不指定项目和分支（默认 changtai-cloud 与 dev）
        YunxiaoCodeScanInstructionDTO i1 = yunxiaoCodeScanService.parseInstruction("代码扫描 梳理实报费用新增的校验逻辑");
        assertNotNull(i1);
        assertEquals("changtai-cloud", i1.getRepoName());
        assertEquals("dev", i1.getBranch());
        assertEquals("梳理实报费用新增的校验逻辑", i1.getQuestion());

        // 2. 仅指定分支（项目名缺省默认 changtai-cloud）
        YunxiaoCodeScanInstructionDTO i2 = yunxiaoCodeScanService.parseInstruction("代码扫描 分支 master 梳理实报费用新增的校验逻辑");
        assertNotNull(i2);
        assertEquals("changtai-cloud", i2.getRepoName());
        assertEquals("master", i2.getBranch());
        assertEquals("梳理实报费用新增的校验逻辑", i2.getQuestion());

        // 3. 仅指定项目（分支名缺省默认 dev）
        YunxiaoCodeScanInstructionDTO i3 = yunxiaoCodeScanService.parseInstruction("扫描项目 my-cloud 梳理实报费用新增的校验逻辑");
        assertNotNull(i3);
        assertEquals("my-cloud", i3.getRepoName());
        assertEquals("dev", i3.getBranch());
        assertEquals("梳理实报费用新增的校验逻辑", i3.getQuestion());

        // 4. 用户之前实际发送的完整指令
        YunxiaoCodeScanInstructionDTO i4 = yunxiaoCodeScanService.parseInstruction("代码扫描 changtai-cloud 分支 dev 梳理实报费用新增的校验逻辑");
        assertNotNull(i4);
        assertEquals("changtai-cloud", i4.getRepoName());
        assertEquals("dev", i4.getBranch());
        assertEquals("梳理实报费用新增的校验逻辑", i4.getQuestion());

        // 5. 空格分隔纯英文项目名+分支名
        YunxiaoCodeScanInstructionDTO i5 = yunxiaoCodeScanService.parseInstruction("分支扫描 other-repo release/1.0 梳理实报");
        assertNotNull(i5);
        assertEquals("other-repo", i5.getRepoName());
        assertEquals("release/1.0", i5.getBranch());
        assertEquals("梳理实报", i5.getQuestion());
    }
}
