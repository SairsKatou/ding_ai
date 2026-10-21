package org.example.service;

import com.ct.common.core.constant.Constants;
import org.example.config.YunxiaoProperties;
import org.example.domain.dto.YunxiaoBranchCreateParam;
import org.example.domain.dto.YunxiaoBranchDTO;
import org.example.domain.dto.YunxiaoRepositoryDTO;
import org.example.domain.dto.YunxiaoRepositoryQueryParam;
import org.example.service.impl.YunxiaoDevopsServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * 云效代码库与分支服务单元测试（Token 鉴权模式）
 *
 * @author zeymo
 */
@ExtendWith(MockitoExtension.class)
public class YunxiaoDevopsServiceTest {

    @Mock
    private RestTemplate yunxiaoRestTemplate;

    @Mock
    private YunxiaoProperties yunxiaoProperties;

    @InjectMocks
    private YunxiaoDevopsServiceImpl yunxiaoDevopsService;

    @BeforeEach
    public void setup() {
        ReflectionTestUtils.setField(yunxiaoDevopsService, "yunxiaoRestTemplate", yunxiaoRestTemplate);
        ReflectionTestUtils.setField(yunxiaoDevopsService, "yunxiaoProperties", yunxiaoProperties);
    }

    /**
     * 测试未配置 accessToken 时抛出异常
     */
    @Test
    public void testCheckAccessToken() {
        Mockito.when(yunxiaoProperties.getAccessToken()).thenReturn(null);

        YunxiaoRepositoryQueryParam param = new YunxiaoRepositoryQueryParam();
        Assertions.assertThrows(IllegalStateException.class, () -> {
            yunxiaoDevopsService.listRepositories(param);
        });
    }

    /**
     * 测试通过个人访问令牌获取代码库与对应分支列表
     */
    @Test
    public void testListRepositoriesWithBranches() {
        Mockito.when(yunxiaoProperties.getAccessToken()).thenReturn("pt-mocktoken12345");
        Mockito.when(yunxiaoProperties.getEndpoint()).thenReturn("openapi-rdc.aliyuncs.com");
        Mockito.when(yunxiaoProperties.getOrganizationId()).thenReturn("mock-org-60b");

        // 模拟仓库 JSON 返回
        String mockReposJson = "[{"
                + "\"id\":1001,"
                + "\"name\":\"demo-repo\","
                + "\"nameWithNamespace\":\"team/demo-repo\","
                + "\"path\":\"demo-repo\","
                + "\"webUrl\":\"https://codeup.aliyun.com/team/demo-repo\","
                + "\"description\":\"测试仓库\","
                + "\"visibility\":\"private\""
                + "}]";

        // 模拟分支 JSON 返回
        String mockBranchesJson = "[{"
                + "\"name\":\"master\","
                + "\"protected\":true,"
                + "\"commit\":{"
                + "\"id\":\"49ddb1677652aa9d2616a5bf4fc460107463e8a7\","
                + "\"message\":\"feat: init project\","
                + "\"authorName\":\"张三\","
                + "\"committedDate\":\"2026-10-19T10:00:00Z\""
                + "}"
                + "}]";

        Mockito.when(yunxiaoRestTemplate.exchange(
                Mockito.contains("/repositories?"),
                Mockito.eq(HttpMethod.GET),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenReturn(ResponseEntity.ok(mockReposJson));

        Mockito.when(yunxiaoRestTemplate.exchange(
                Mockito.contains("/repositories/1001/branches?"),
                Mockito.eq(HttpMethod.GET),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenReturn(ResponseEntity.ok(mockBranchesJson));

        YunxiaoRepositoryQueryParam queryParam = new YunxiaoRepositoryQueryParam();
        queryParam.setPage(Constants.ONE_LONG);
        queryParam.setPerPage(Constants.TWENTY_LONG);

        List<YunxiaoRepositoryDTO> result = yunxiaoDevopsService.listRepositoriesWithBranches(queryParam);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());

        YunxiaoRepositoryDTO repoDTO = result.get(0);
        Assertions.assertEquals("demo-repo", repoDTO.getName());
        Assertions.assertNotNull(repoDTO.getBranches());
        Assertions.assertEquals(1, repoDTO.getBranches().size());

        YunxiaoBranchDTO branchDTO = repoDTO.getBranches().get(0);
        Assertions.assertEquals("master", branchDTO.getName());
        Assertions.assertTrue(branchDTO.getIsProtected());
        Assertions.assertEquals("feat: init project", branchDTO.getCommitMessage());
        Assertions.assertEquals("张三", branchDTO.getCommitAuthorName());

        // 验证聊天格式化输出
        String chatText = yunxiaoDevopsService.formatRepositoriesAndBranchesForChat("");
        Assertions.assertTrue(chatText.contains("demo-repo"));
        Assertions.assertTrue(chatText.contains("master"));
        Assertions.assertTrue(chatText.contains("[保护]"));
    }

    /**
     * 测试创建分支
     */
    @Test
    public void testCreateBranch() {
        Mockito.when(yunxiaoProperties.getAccessToken()).thenReturn("pt-mocktoken12345");
        Mockito.when(yunxiaoProperties.getEndpoint()).thenReturn("openapi-rdc.aliyuncs.com");
        Mockito.when(yunxiaoProperties.getOrganizationId()).thenReturn("mock-org-60b");

        String mockCreateBranchJson = "{"
                + "\"name\":\"feature/test-branch\","
                + "\"protected\":false,"
                + "\"commit\":{"
                + "\"id\":\"789def0123\","
                + "\"message\":\"chore: branch point\","
                + "\"authorName\":\"李四\","
                + "\"committedDate\":\"2026-10-19T11:00:00Z\""
                + "}"
                + "}";

        Mockito.when(yunxiaoRestTemplate.exchange(
                Mockito.contains("/repositories/1001/branches?"),
                Mockito.eq(HttpMethod.POST),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenReturn(ResponseEntity.ok(mockCreateBranchJson));

        YunxiaoBranchCreateParam createParam = YunxiaoBranchCreateParam.builder()
                .repositoryId("1001")
                .branchName("feature/test-branch")
                .ref("master")
                .build();

        YunxiaoBranchDTO result = yunxiaoDevopsService.createBranch(createParam);

        Assertions.assertNotNull(result);
        Assertions.assertEquals("feature/test-branch", result.getName());
        Assertions.assertFalse(result.getIsProtected());
        Assertions.assertEquals("789def0123", result.getCommitId());

        // 测试群聊创建分支指令
        String chatResult = yunxiaoDevopsService.createBranchForChat("创建分支 1001 feature/test-branch master");
        Assertions.assertTrue(chatResult.contains("分支创建成功"));
        Assertions.assertTrue(chatResult.contains("feature/test-branch"));
    }
}
