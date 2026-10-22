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
