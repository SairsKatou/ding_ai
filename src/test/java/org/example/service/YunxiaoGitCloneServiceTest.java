package org.example.service;

import com.alibaba.fastjson.JSON;
import com.ct.common.core.constant.Constants;
import org.example.config.YunxiaoProperties;
import org.example.domain.dto.YunxiaoCodeSnippetDTO;
import org.example.domain.dto.YunxiaoRepoCacheMetadata;
import org.example.domain.dto.YunxiaoRepositoryDTO;
import org.example.service.impl.CodeSearchKeywordServiceImpl;
import org.example.service.impl.YunxiaoGitCloneServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 云效本地 Git 浅克隆与缓存管理单元测试
 *
 * @author zeymo
 */
@ExtendWith(MockitoExtension.class)
public class YunxiaoGitCloneServiceTest {

    @InjectMocks
    private YunxiaoGitCloneServiceImpl yunxiaoGitCloneService;

    @Mock
    private YunxiaoDevopsService yunxiaoDevopsService;

    private YunxiaoProperties yunxiaoProperties;

    private File tempCacheDir;

    @BeforeEach
    public void setup() throws IOException {
        tempCacheDir = new File(System.getProperty("java.io.tmpdir"), "unit-test-repo-cache-" + System.currentTimeMillis());
        tempCacheDir.mkdirs();

        yunxiaoProperties = new YunxiaoProperties();
        yunxiaoProperties.setCloneCacheDir(tempCacheDir.getAbsolutePath());
        yunxiaoProperties.setCloneCacheExpireHours(Constants.TWO);

        ReflectionTestUtils.setField(yunxiaoGitCloneService, "yunxiaoProperties", yunxiaoProperties);
        ReflectionTestUtils.setField(yunxiaoGitCloneService, "yunxiaoDevopsService", yunxiaoDevopsService);
        ReflectionTestUtils.setField(yunxiaoGitCloneService, "codeSearchKeywordService", new CodeSearchKeywordServiceImpl());
    }

    @AfterEach
    public void tearDown() {
        if (tempCacheDir != null && tempCacheDir.exists()) {
            try (Stream<Path> stream = Files.walk(tempCacheDir.toPath())) {
                stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 测试当本地存在克隆且未超过 2 小时时，直接命中缓存并复用，不重复克隆
     */
    @Test
    public void testPrepareLocalRepositoryCacheHitWithinTwoHours() throws IOException {
        String repoId = "6120951";
        String repoName = "changtai-cloud";
        String branch = "dev";

        File targetDir = new File(tempCacheDir, repoName + "_" + branch);
        targetDir.mkdirs();

        // 模拟 .git 目录存在
        File gitDir = new File(targetDir, ".git");
        gitDir.mkdirs();

        // 模拟 2 小时内的缓存元数据
        long now = System.currentTimeMillis();
        long expireAt = now + Constants.TWO_HOURS_MILLIS;
        YunxiaoRepoCacheMetadata meta = YunxiaoRepoCacheMetadata.builder()
                .repositoryId(repoId)
                .repositoryName(repoName)
                .branch(branch)
                .cloneDir(targetDir.getAbsolutePath())
                .cachedAt(now)
                .expireAt(expireAt)
                .build();

        File metaFile = new File(targetDir, "_cache_meta.json");
        Files.writeString(metaFile.toPath(), JSON.toJSONString(meta), StandardCharsets.UTF_8);

        // 调用 prepareLocalRepository，预期直接返回本地目录，不执行 git clone
        File resultDir = yunxiaoGitCloneService.prepareLocalRepository(repoId, repoName, branch, "https://mock.git");

        assertNotNull(resultDir);
        assertEquals(targetDir.getAbsolutePath(), resultDir.getAbsolutePath());
        assertTrue(metaFile.exists());
    }

    /**
     * 测试本地全量扫描匹配功能
     */
    @Test
    public void testScanLocalRepository() throws IOException {
        File repoDir = new File(tempCacheDir, "test-repo");
        repoDir.mkdirs();

        // 创建测试控制器文件
        File controllerFile = new File(repoDir, "ScmActualReimbursementController.java");
        String code = "package com.ct.scm.controller;\n"
                + "public class ScmActualReimbursementController {\n"
                + "    // 实报业务接口调用\n"
                + "    public void saveActualReport() {}\n"
                + "}\n";
        Files.writeString(controllerFile.toPath(), code, StandardCharsets.UTF_8);

        List<YunxiaoCodeSnippetDTO> snippets = yunxiaoGitCloneService.scanLocalRepository(repoDir, "实报", Constants.TEN);

        assertNotNull(snippets);
        assertFalse(snippets.isEmpty());
        assertEquals("ScmActualReimbursementController.java", snippets.get(Constants.ZERO).getFilePath());
        assertTrue(snippets.get(Constants.ZERO).getSnippet().contains("实报业务接口调用"));
        // 短文件也带行号
        assertTrue(snippets.get(Constants.ZERO).getSnippet().contains("L3:"));
    }

    /**
     * 测试按 Token 专属度排序：命中「拆分」的 ServiceImpl 排在只命中泛化词「对账」的 Controller 之前，
     * 且片段跳过 import、优先保留命中关键词的方法
     */
    @Test
    public void testScanLocalRepositoryRankingBySpecificToken() throws IOException {
        File repoDir = new File(tempCacheDir, "rank-repo");
        File controllerDir = new File(repoDir, "controller");
        File serviceDir = new File(repoDir, "service");
        controllerDir.mkdirs();
        serviceDir.mkdirs();

        // 多个只命中泛化词 statement 的 Controller
        for (int i = Constants.ZERO; i < Constants.FIVE; i++) {
            Files.writeString(new File(controllerDir, "Statement" + i + "Controller.java").toPath(),
                    "public class Statement" + i + "Controller { /* 对账单 */ }\n", StandardCharsets.UTF_8);
        }
        // 命中专属词「拆分 / spilt」的核心实现类
        StringBuilder serviceCode = new StringBuilder();
        for (int i = Constants.ZERO; i < Constants.TWO_HUNDRED; i++) {
            serviceCode.append("import com.example.statement.Dep").append(i).append(";\n");
        }
        serviceCode.append("public class HeadStatementServiceImpl {\n");
        for (int i = Constants.ZERO; i < Constants.HUNDRED; i++) {
            serviceCode.append("    private int statementField").append(i).append(";\n");
        }
        serviceCode.append("    /** 对账单拆分 */\n")
                .append("    public void generateSplitData(String statementCode) {\n")
                .append("        saveSpiltDetail(statementCode);\n")
                .append("    }\n}\n");
        Files.writeString(new File(serviceDir, "HeadStatementServiceImpl.java").toPath(), serviceCode.toString(), StandardCharsets.UTF_8);

        List<YunxiaoCodeSnippetDTO> snippets = yunxiaoGitCloneService.scanLocalRepository(repoDir, "对账单明细拆分", Constants.TEN);

        assertEquals(Constants.FIVE + Constants.ONE, snippets.size());
        assertEquals("service/HeadStatementServiceImpl.java", snippets.get(Constants.ZERO).getFilePath());
        String snippet = snippets.get(Constants.ZERO).getSnippet();
        assertTrue(snippet.contains("public void generateSplitData"));
        assertFalse(snippet.contains("import com.example"));
    }

    /**
     * 测试大文件片段长度截断
     */
    @Test
    public void testScanLocalRepositorySnippetLimit() throws IOException {
        File repoDir = new File(tempCacheDir, "limit-repo");
        repoDir.mkdirs();

        // 每隔 50 行出现一次关键词，片段需被截断
        StringBuilder bigCode = new StringBuilder("public class CommonService {\n");
        for (int i = Constants.ZERO; i < Constants.THREE_THOUSAND * Constants.TEN; i++) {
            bigCode.append(i % Constants.FIFTY == Constants.ZERO ? "    // reimbursement 调用 " : "    int field").append(i).append(";\n");
        }
        bigCode.append("}\n");
        Files.writeString(new File(repoDir, "CommonService.java").toPath(), bigCode.toString(), StandardCharsets.UTF_8);

        List<YunxiaoCodeSnippetDTO> snippets = yunxiaoGitCloneService.scanLocalRepository(repoDir, "reimbursement", Constants.TEN);

        assertEquals(Constants.ONE, snippets.size());
        String bigSnippet = snippets.get(Constants.ZERO).getSnippet();
        // 相关度第 1 的文件上限为 4 倍单文件长度
        assertTrue(bigSnippet.length() <= Constants.FOUR_THOUSAND * Constants.EIGHT + Constants.HUNDRED);
        assertTrue(bigSnippet.contains("片段过长已截断"));
    }

    /**
     * 测试群聊克隆分支指令：仓库名不填时默认使用 changtai-cloud，分支名不填时默认使用 dev
     */
    @Test
    public void testCloneBranchForChatDefaultRepoAndBranch() {
        when(yunxiaoDevopsService.listRepositories(any())).thenReturn(Collections.emptyList());

        String result = yunxiaoGitCloneService.cloneBranchForChat("克隆分支");
        assertNotNull(result);
        assertTrue(result.contains("changtai-cloud"));
    }

    /**
     * 测试群聊克隆分支指令：仅指定分支名，仓库名缺省为 changtai-cloud
     */
    @Test
    public void testCloneBranchForChatDefaultRepoWithBranch() {
        when(yunxiaoDevopsService.listRepositories(any())).thenReturn(Collections.emptyList());

        String result = yunxiaoGitCloneService.cloneBranchForChat("克隆分支 分支 master");
        assertNotNull(result);
        assertTrue(result.contains("changtai-cloud"));
    }

    /**
     * 测试群聊克隆分支时未找到仓库
     */
    @Test
    public void testCloneBranchForChatRepoNotFound() {
        when(yunxiaoDevopsService.listRepositories(any())).thenReturn(Collections.emptyList());

        String result = yunxiaoGitCloneService.cloneBranchForChat("克隆分支 not-exist-repo");
        assertNotNull(result);
        assertTrue(result.contains("克隆分支失败"));
    }
}
