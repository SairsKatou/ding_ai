package org.example.service;

import com.ct.common.core.constant.Constants;
import com.ct.common.core.enums.PipelineRunStatusEnum;
import org.example.config.YunxiaoProperties;
import org.example.domain.dto.YunxiaoBranchCreateParam;
import org.example.domain.dto.YunxiaoBranchDTO;
import org.example.domain.dto.YunxiaoPipelineCancelDTO;
import org.example.domain.dto.YunxiaoPipelineCancelParam;
import org.example.domain.dto.YunxiaoPipelineDTO;
import org.example.domain.dto.YunxiaoPipelineGroupDTO;
import org.example.domain.dto.YunxiaoPipelineGroupQueryParam;
import org.example.domain.dto.YunxiaoPipelineQueryParam;
import org.example.domain.dto.YunxiaoPipelineRunDTO;
import org.example.domain.dto.YunxiaoPipelineRunParam;
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
 * 云效代码库、分支与流水线服务单元测试（Token 鉴权模式）
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
		Assertions.assertEquals(Constants.ONE, result.size());

		YunxiaoRepositoryDTO repoDTO = result.get(Constants.ZERO);
		Assertions.assertEquals("demo-repo", repoDTO.getName());
		Assertions.assertNotNull(repoDTO.getBranches());
		Assertions.assertEquals(Constants.ONE, repoDTO.getBranches().size());

		YunxiaoBranchDTO branchDTO = repoDTO.getBranches().get(Constants.ZERO);
		Assertions.assertEquals("master", branchDTO.getName());
		Assertions.assertTrue(branchDTO.getIsProtected());
		Assertions.assertEquals("feat: init project", branchDTO.getCommitMessage());
		Assertions.assertEquals("张三", branchDTO.getCommitAuthorName());

		// 验证聊天格式化输出
		String chatText = yunxiaoDevopsService.formatRepositoriesAndBranchesForChat("");
		Assertions.assertTrue(chatText.contains("demo-repo"));
		Assertions.assertTrue(chatText.contains("master"));
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

		String mockReposJson = "[{\"id\":1001,\"name\":\"changtai-cloud\",\"path\":\"changtai-cloud\"}]";
		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/repositories?"),
				Mockito.eq(HttpMethod.GET),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockReposJson));

		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/branches?"),
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

		// 测试群聊创建分支指令（省略仓库名，默认 changtai-cloud）
		String defaultRepoChatResult = yunxiaoDevopsService.createBranchForChat("创建分支 feature/test-branch dev");
		Assertions.assertTrue(defaultRepoChatResult.contains("分支创建成功"));
		Assertions.assertTrue(defaultRepoChatResult.contains("changtai-cloud"));
	}

	/**
	 * 测试查询云效流水线列表
	 */
	@Test
	public void testListPipelines() {
		Mockito.when(yunxiaoProperties.getAccessToken()).thenReturn("pt-mocktoken12345");
		Mockito.when(yunxiaoProperties.getEndpoint()).thenReturn("openapi-rdc.aliyuncs.com");
		Mockito.when(yunxiaoProperties.getOrganizationId()).thenReturn("mock-org-60b");

		String mockPipelinesJson = "[{"
				+ "\"pipelineId\":556677,"
				+ "\"pipelineName\":\"changtai-cloud-prod-pipeline\","
				+ "\"creatorAccountId\":\"user-8899\","
				+ "\"envName\":\"prod\","
				+ "\"gmtCreate\":\"2026-10-20 12:00:00\""
				+ "}]";

		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelines?"),
				Mockito.eq(HttpMethod.GET),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockPipelinesJson));

		YunxiaoPipelineQueryParam queryParam = new YunxiaoPipelineQueryParam();
		queryParam.setPipelineName("changtai");
		queryParam.setPageNum(Constants.ONE);
		queryParam.setPageSize(Constants.TEN);

		List<YunxiaoPipelineDTO> result = yunxiaoDevopsService.listPipelines(queryParam);

		Assertions.assertNotNull(result);
		Assertions.assertEquals(Constants.ONE, result.size());
		YunxiaoPipelineDTO dto = result.get(Constants.ZERO);
		Assertions.assertEquals(556677L, dto.getPipelineId());
		Assertions.assertEquals("changtai-cloud-prod-pipeline", dto.getPipelineName());
	}

	/**
	 * 测试触发云效流水线发布运行
	 */
	@Test
	public void testRunPipeline() {
		Mockito.when(yunxiaoProperties.getAccessToken()).thenReturn("pt-mocktoken12345");
		Mockito.when(yunxiaoProperties.getEndpoint()).thenReturn("openapi-rdc.aliyuncs.com");
		Mockito.when(yunxiaoProperties.getOrganizationId()).thenReturn("mock-org-60b");

		String mockRunResultJson = "{"
				+ "\"pipelineRunId\":99887766,"
				+ "\"status\":\"RUNNING\""
				+ "}";

		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelines/556677/run"),
				Mockito.eq(HttpMethod.POST),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockRunResultJson));

		YunxiaoPipelineRunParam runParam = YunxiaoPipelineRunParam.builder()
				.pipelineId(556677L)
				.branchName("feature/order-v1")
				.repositoryId("changtai-cloud")
				.comment("自动化发布测试")
				.build();

		YunxiaoPipelineRunDTO result = yunxiaoDevopsService.runPipeline(runParam);

		Assertions.assertNotNull(result);
		Assertions.assertEquals(556677L, result.getPipelineId());
		Assertions.assertEquals(99887766L, result.getPipelineRunId());
		Assertions.assertEquals("feature/order-v1", result.getBranchName());
		Assertions.assertEquals(PipelineRunStatusEnum.RUNNING.getCode(), result.getStatus());
	}

	/**
	 * 测试钉钉群聊分支发布指令解析与触发
	 */
	@Test
	public void testReleaseBranchForChat() {
		Mockito.when(yunxiaoProperties.getAccessToken()).thenReturn("pt-mocktoken12345");
		Mockito.when(yunxiaoProperties.getEndpoint()).thenReturn("openapi-rdc.aliyuncs.com");
		Mockito.when(yunxiaoProperties.getOrganizationId()).thenReturn("mock-org-60b");

		String mockPipelinesJson = "[{"
				+ "\"pipelineId\":556677,"
				+ "\"pipelineName\":\"changtai-cloud-pipeline\""
				+ "}]";

		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelines?"),
				Mockito.eq(HttpMethod.GET),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockPipelinesJson));

		String mockRunResultJson = "{\"pipelineRunId\":88990011,\"status\":\"RUNNING\"}";
		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelines/556677/run"),
				Mockito.eq(HttpMethod.POST),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockRunResultJson));

		// 测试指令1：仅带分支名（默认测试环境 test）
		String chatResult1 = yunxiaoDevopsService.releaseBranchForChat("发布分支 feature/order-v1");
		Assertions.assertTrue(chatResult1.contains("分支发布流水线已触发"));
		Assertions.assertTrue(chatResult1.contains("feature/order-v1"));
		Assertions.assertTrue(chatResult1.contains("88990011"));
		Assertions.assertTrue(chatResult1.contains("test（测试环境）"));

		// 测试指令2：带仓库名、分支名、流水线ID、指定生产环境
		String chatResult2 = yunxiaoDevopsService.releaseBranchForChat("发布分支 changtai-cloud feature/order-v1 556677 生产");
		Assertions.assertTrue(chatResult2.contains("分支发布流水线已触发"));
		Assertions.assertTrue(chatResult2.contains("556677"));
		Assertions.assertTrue(chatResult2.contains("prod（生产环境）"));

		// 测试指令3：自定义环境 key=value 传参
		String chatResult3 = yunxiaoDevopsService.releaseBranchForChat("发布分支 feature/order-v1 环境=gray");
		Assertions.assertTrue(chatResult3.contains("分支发布流水线已触发"));
		Assertions.assertTrue(chatResult3.contains("gray（自定义环境）"));

		// 测试空指令返回指南
		String helpResult = yunxiaoDevopsService.releaseBranchForChat("发布分支");
		Assertions.assertTrue(helpResult.contains("分支发布指令说明"));
	}

	/**
	 * 测试终止/取消流水线运行
	 */
	@Test
	public void testStopPipelineRun() {
		Mockito.when(yunxiaoProperties.getAccessToken()).thenReturn("pt-mocktoken12345");
		Mockito.when(yunxiaoProperties.getEndpoint()).thenReturn("openapi-rdc.aliyuncs.com");
		Mockito.when(yunxiaoProperties.getOrganizationId()).thenReturn("mock-org-60b");

		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelines/556677/pipelineRuns/99887766/stop"),
				Mockito.eq(HttpMethod.PUT),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok("{\"success\":true}"));

		YunxiaoPipelineCancelParam cancelParam = YunxiaoPipelineCancelParam.builder()
				.pipelineId(556677L)
				.pipelineRunId(99887766L)
				.branchName("feature/order-v1")
				.build();

		YunxiaoPipelineCancelDTO result = yunxiaoDevopsService.stopPipelineRun(cancelParam);

		Assertions.assertNotNull(result);
		Assertions.assertEquals(556677L, result.getPipelineId());
		Assertions.assertEquals(99887766L, result.getPipelineRunId());
		Assertions.assertTrue(result.getSuccess());
	}

	/**
	 * 测试钉钉群聊取消分支发布指令解析与执行
	 */
	@Test
	public void testCancelReleaseBranchForChat() {
		Mockito.when(yunxiaoProperties.getAccessToken()).thenReturn("pt-mocktoken12345");
		Mockito.when(yunxiaoProperties.getEndpoint()).thenReturn("openapi-rdc.aliyuncs.com");
		Mockito.when(yunxiaoProperties.getOrganizationId()).thenReturn("mock-org-60b");

		// 模拟查询流水线匹配
		String mockPipelinesJson = "[{\"pipelineId\":556677,\"pipelineName\":\"changtai-cloud-pipeline\"}]";
		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelines?"),
				Mockito.eq(HttpMethod.GET),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockPipelinesJson));

		// 模拟通过分支查询运行中实例
		String mockRunsJson = "[{\"pipelineRunId\":778899,\"branchModeBranchs\":[\"feature/order-v1\"],\"status\":\"RUNNING\"}]";
		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelines/556677/runs?"),
				Mockito.eq(HttpMethod.GET),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockRunsJson));

		// 模拟终止运行响应
		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelines/556677/pipelineRuns/778899/stop"),
				Mockito.eq(HttpMethod.PUT),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok("{\"success\":true}"));

		// 测试通过分支名取消（默认环境）
		String chatResult = yunxiaoDevopsService.cancelReleaseBranchForChat("取消发布 feature/order-v1");
		Assertions.assertTrue(chatResult.contains("分支发布已成功取消"));
		Assertions.assertTrue(chatResult.contains("feature/order-v1"));
		Assertions.assertTrue(chatResult.contains("778899"));
		Assertions.assertTrue(chatResult.contains("test（测试环境）"));

		// 测试指定开发环境取消
		String chatResultDev = yunxiaoDevopsService.cancelReleaseBranchForChat("取消发布 feature/order-v1 开发");
		Assertions.assertTrue(chatResultDev.contains("分支发布已成功取消"));
		Assertions.assertTrue(chatResultDev.contains("dev（开发环境）"));

		// 测试空指令返回指南
		String helpResult = yunxiaoDevopsService.cancelReleaseBranchForChat("取消发布");
		Assertions.assertTrue(helpResult.contains("取消发布分支指令说明"));
	}

	/**
	 * 测试查询流水线分组列表
	 */
	@Test
	public void testListPipelineGroups() {
		Mockito.when(yunxiaoProperties.getAccessToken()).thenReturn("pt-mocktoken12345");
		Mockito.when(yunxiaoProperties.getEndpoint()).thenReturn("openapi-rdc.aliyuncs.com");
		Mockito.when(yunxiaoProperties.getOrganizationId()).thenReturn("mock-org-60b");

		String mockGroupsJson = "[{"
				+ "\"id\":1001,"
				+ "\"name\":\"微服务订单中心\","
				+ "\"creatorAccountId\":\"user-8899\""
				+ "}]";

		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelineGroups"),
				Mockito.eq(HttpMethod.GET),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockGroupsJson));

		YunxiaoPipelineGroupQueryParam queryParam = new YunxiaoPipelineGroupQueryParam();
		queryParam.setGroupName("订单");

		List<YunxiaoPipelineGroupDTO> result = yunxiaoDevopsService.listPipelineGroups(queryParam);

		Assertions.assertNotNull(result);
		Assertions.assertEquals(Constants.ONE, result.size());
		Assertions.assertEquals(1001L, result.get(Constants.ZERO).getGroupId());
		Assertions.assertEquals("微服务订单中心", result.get(Constants.ZERO).getGroupName());

		// 测试群聊展示流水线分组
		String chatResult = yunxiaoDevopsService.listPipelineGroupsForChat("流水线分组 订单");
		Assertions.assertTrue(chatResult.contains("云效流水线分组列表"));
		Assertions.assertTrue(chatResult.contains("微服务订单中心"));
	}

	/**
	 * 测试按流水线分组 + 分支名发布
	 */
	@Test
	public void testReleaseBranchWithPipelineGroup() {
		Mockito.when(yunxiaoProperties.getAccessToken()).thenReturn("pt-mocktoken12345");
		Mockito.when(yunxiaoProperties.getEndpoint()).thenReturn("openapi-rdc.aliyuncs.com");
		Mockito.when(yunxiaoProperties.getOrganizationId()).thenReturn("mock-org-60b");

		// 模拟查询分组列表返回
		String mockGroupsJson = "[{\"id\":1001,\"name\":\"营销系统\"}]";
		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelineGroups"),
				Mockito.eq(HttpMethod.GET),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockGroupsJson));

		// 模拟查询该分组下的流水线列表
		String mockPipelinesJson = "[{\"pipelineId\":778899,\"pipelineName\":\"marketing-pipeline\",\"groupId\":1001}]";
		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelines?"),
				Mockito.eq(HttpMethod.GET),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockPipelinesJson));

		// 模拟触发流水线运行
		String mockRunResultJson = "{\"pipelineRunId\":665544,\"status\":\"RUNNING\"}";
		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelines/778899/run"),
				Mockito.eq(HttpMethod.POST),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockRunResultJson));

		// 测试群聊指令：发布分支 分组=营销系统 分支=feature/coupon-v1
		String result = yunxiaoDevopsService.releaseBranchForChat("发布分支 分组=营销系统 分支=feature/coupon-v1");
		Assertions.assertTrue(result.contains("分支发布流水线已触发"));
		Assertions.assertTrue(result.contains("feature/coupon-v1"));
		Assertions.assertTrue(result.contains("665544"));
		Assertions.assertTrue(result.contains("营销系统"));
	}

	/**
	 * 测试按流水线分组 + 分支名取消发布
	 */
	@Test
	public void testCancelReleaseBranchWithPipelineGroup() {
		Mockito.when(yunxiaoProperties.getAccessToken()).thenReturn("pt-mocktoken12345");
		Mockito.when(yunxiaoProperties.getEndpoint()).thenReturn("openapi-rdc.aliyuncs.com");
		Mockito.when(yunxiaoProperties.getOrganizationId()).thenReturn("mock-org-60b");

		// 模拟查询分组列表返回
		String mockGroupsJson = "[{\"id\":1001,\"name\":\"营销系统\"}]";
		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelineGroups"),
				Mockito.eq(HttpMethod.GET),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockGroupsJson));

		// 模拟查询该分组下的流水线列表
		String mockPipelinesJson = "[{\"pipelineId\":778899,\"pipelineName\":\"marketing-pipeline\",\"groupId\":1001}]";
		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelines?"),
				Mockito.eq(HttpMethod.GET),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockPipelinesJson));

		// 模拟查询该流水线下正在运行的实例
		String mockRunsJson = "[{\"pipelineRunId\":554433,\"branchModeBranchs\":[\"feature/coupon-v1\"],\"status\":\"RUNNING\"}]";
		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelines/778899/runs?"),
				Mockito.eq(HttpMethod.GET),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockRunsJson));

		// 模拟终止运行实例
		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelines/778899/pipelineRuns/554433/stop"),
				Mockito.eq(HttpMethod.PUT),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok("{\"success\":true}"));

		// 测试群聊指令：取消发布 分组=营销系统 分支=feature/coupon-v1
		String result = yunxiaoDevopsService.cancelReleaseBranchForChat("取消发布 分组=营销系统 分支=feature/coupon-v1");
		Assertions.assertTrue(result.contains("分支发布已成功取消"));
		Assertions.assertTrue(result.contains("feature/coupon-v1"));
		Assertions.assertTrue(result.contains("554433"));
		Assertions.assertTrue(result.contains("营销系统"));
	}

	/**
	 * 测试先查询流水线分组，再返回各分组下具体流水线列表
	 */
	@Test
	public void testListPipelineGroupsWithPipelines() {
		Mockito.when(yunxiaoProperties.getAccessToken()).thenReturn("pt-mocktoken12345");
		Mockito.when(yunxiaoProperties.getEndpoint()).thenReturn("openapi-rdc.aliyuncs.com");
		Mockito.when(yunxiaoProperties.getOrganizationId()).thenReturn("mock-org-60b");

		// 模拟查询分组：测试环境 (1001), 生产环境 (1002)
		String mockGroupsJson = "["
				+ "{\"id\":1001,\"name\":\"测试环境\"},"
				+ "{\"id\":1002,\"name\":\"生产环境\"}"
				+ "]";
		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelineGroups"),
				Mockito.eq(HttpMethod.GET),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockGroupsJson));

		// 模拟查询组织下所有流水线
		String mockPipelinesJson = "["
				+ "{\"pipelineId\":111,\"pipelineName\":\"ct-modules-scm-测试环境\",\"groupId\":1001},"
				+ "{\"pipelineId\":222,\"pipelineName\":\"ct-gateway-测试环境\",\"groupId\":1001},"
				+ "{\"pipelineId\":333,\"pipelineName\":\"ct-modules-scm-生产环境\",\"groupId\":1002}"
				+ "]";
		Mockito.when(yunxiaoRestTemplate.exchange(
				Mockito.contains("/pipelines?"),
				Mockito.eq(HttpMethod.GET),
				Mockito.any(HttpEntity.class),
				Mockito.eq(String.class)
		)).thenReturn(ResponseEntity.ok(mockPipelinesJson));

		YunxiaoPipelineGroupQueryParam queryParam = new YunxiaoPipelineGroupQueryParam();
		List<YunxiaoPipelineGroupDTO> result = yunxiaoDevopsService.listPipelineGroupsWithPipelines(queryParam);

		Assertions.assertNotNull(result);
		Assertions.assertEquals(Constants.TWO, result.size());

		// 验证测试环境分组下的具体流水线
		YunxiaoPipelineGroupDTO testGroup = result.get(Constants.ZERO);
		Assertions.assertEquals(1001L, testGroup.getGroupId());
		Assertions.assertEquals("测试环境", testGroup.getGroupName());
		Assertions.assertEquals(Constants.TWO, testGroup.getPipelines().size());
		Assertions.assertEquals("ct-modules-scm-测试环境", testGroup.getPipelines().get(Constants.ZERO).getPipelineName());
		Assertions.assertEquals("ct-gateway-测试环境", testGroup.getPipelines().get(Constants.ONE).getPipelineName());

		// 验证生产环境分组下的具体流水线
		YunxiaoPipelineGroupDTO prodGroup = result.get(Constants.ONE);
		Assertions.assertEquals(1002L, prodGroup.getGroupId());
		Assertions.assertEquals("生产环境", prodGroup.getGroupName());
		Assertions.assertEquals(Constants.ONE, prodGroup.getPipelines().size());
		Assertions.assertEquals("ct-modules-scm-生产环境", prodGroup.getPipelines().get(Constants.ZERO).getPipelineName());

		// 测试群聊展示流水线分组详情
		String chatResult = yunxiaoDevopsService.listPipelineGroupsForChat("流水线分组详情");
		Assertions.assertTrue(chatResult.contains("云效流水线分组及下属具体流水线"));
		Assertions.assertTrue(chatResult.contains("测试环境"));
		Assertions.assertTrue(chatResult.contains("ct-modules-scm-测试环境"));
	}
}
