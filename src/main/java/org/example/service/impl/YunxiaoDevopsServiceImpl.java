package org.example.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ct.common.core.constant.Constants;
import com.ct.common.core.enums.PipelineRunStatusEnum;
import com.ct.common.core.enums.ReleaseEnvironmentEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.example.config.YunxiaoProperties;
import org.example.domain.dto.YunxiaoBranchCreateParam;
import org.example.domain.dto.YunxiaoBranchDTO;
import org.example.domain.dto.YunxiaoBranchQueryParam;
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
import org.example.service.YunxiaoDevopsService;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 云效 DevOps 代码库、分支与流水线发布管理实现类（通过个人访问令牌）
 *
 * @author zeymo
 */
@Slf4j
@Service
public class YunxiaoDevopsServiceImpl implements YunxiaoDevopsService {

	/**
	 * 云效令牌 Header 键名
	 */
	private static final String YUNXIAO_TOKEN_HEADER = "x-yunxiao-token";

	/**
	 * 云效 Codeup 基础 API 路径
	 */
	private static final String CODEUP_BASE_PATH = "/oapi/v1/codeup";

	/**
	 * 默认源分支
	 */
	private static final String DEFAULT_REF_BRANCH = "master";

	@Resource
	private RestTemplate yunxiaoRestTemplate;

	@Resource
	private YunxiaoProperties yunxiaoProperties;

	/**
	 * 查询云效项目(代码库)列表
	 *
	 * @param queryParam 查询参数
	 * @return 代码库列表
	 */
	@Override
	public List<YunxiaoRepositoryDTO> listRepositories(YunxiaoRepositoryQueryParam queryParam) {
		// 校验令牌是否配置
		checkAccessToken();

		// 构建代码库列表查询 URL
		String url = buildRepositoriesUrl(queryParam);
		// 创建身份认证实体
		HttpEntity<?> entity = createAuthEntity();

		try {
			log.info("开始请求云效查询代码库列表, url: {}", url);
			ResponseEntity<String> response = yunxiaoRestTemplate.exchange(url, HttpMethod.GET, entity, String.class);
			String body = response.getBody();

			if (StringUtils.isBlank(body)) {
				log.warn("云效代码库查询返回为空响应");
				return Collections.emptyList();
			}

			// 解析响应为 JSON 数组
			JSONArray jsonArray = parseToJsonArray(body);
			if (jsonArray == null || jsonArray.isEmpty()) {
				log.info("未获取到云效代码库数据");
				return Collections.emptyList();
			}

			List<YunxiaoRepositoryDTO> list = new ArrayList<>(jsonArray.size());
			for (int i = Constants.ZERO; i < jsonArray.size(); i++) {
				JSONObject item = jsonArray.getJSONObject(i);
				// 转换为代码库 DTO
				list.add(convertToRepositoryDTO(item));
			}

			log.info("成功获取云效代码库列表, 数量: {}", list.size());
			return list;
		} catch (HttpStatusCodeException e) {
			String responseBody = e.getResponseBodyAsString();
			log.error("云效代码库查询 HTTP 异常, 状态码: {}, 响应体: {}", e.getStatusCode(), responseBody, e);
			// 提取错误提示并抛出业务异常
			throw new RuntimeException("云效代码库查询失败: " + extractErrorMessage(responseBody, e.getMessage()), e);
		} catch (Exception e) {
			log.error("云效代码库查询发生未预期异常: {}", e.getMessage(), e);
			throw new RuntimeException("云效代码库查询异常: " + e.getMessage(), e);
		}
	}

	/**
	 * 查询指定代码库的分支列表
	 *
	 * @param repositoryId 代码库ID或全路径
	 * @param queryParam   分支查询参数
	 * @return 分支列表
	 */
	@Override
	public List<YunxiaoBranchDTO> listRepositoryBranches(String repositoryId, YunxiaoBranchQueryParam queryParam) {
		// 校验令牌是否配置
		checkAccessToken();

		if (StringUtils.isBlank(repositoryId)) {
			throw new IllegalArgumentException("代码库ID不能为空");
		}

		// 解析代码库标识
		String resolvedRepoId = resolveRepositoryId(repositoryId);
		// 构建分支列表请求 URL
		String url = buildBranchesUrl(resolvedRepoId, queryParam);
		// 创建身份认证实体
		HttpEntity<?> entity = createAuthEntity();

		try {
			log.info("开始请求云效查询代码库 [{}] 的分支列表, url: {}", resolvedRepoId, url);
			ResponseEntity<String> response = yunxiaoRestTemplate.exchange(url, HttpMethod.GET, entity, String.class);
			String body = response.getBody();

			if (StringUtils.isBlank(body)) {
				log.warn("云效分支查询返回为空响应");
				return Collections.emptyList();
			}

			// 解析响应为 JSON 数组
			JSONArray jsonArray = parseToJsonArray(body);
			if (jsonArray == null || jsonArray.isEmpty()) {
				log.info("代码库 [{}] 未查询到任何分支", resolvedRepoId);
				return Collections.emptyList();
			}

			List<YunxiaoBranchDTO> list = new ArrayList<>(jsonArray.size());
			for (int i = Constants.ZERO; i < jsonArray.size(); i++) {
				JSONObject item = jsonArray.getJSONObject(i);
				// 转换为分支 DTO
				list.add(convertToBranchDTO(item));
			}

			log.info("成功获取代码库 [{}] 分支列表, 数量: {}", resolvedRepoId, list.size());
			return list;
		} catch (HttpStatusCodeException e) {
			String responseBody = e.getResponseBodyAsString();
			log.error("云效分支查询 HTTP 异常, 状态码: {}, 响应体: {}", e.getStatusCode(), responseBody, e);
			// 提取错误提示并抛出业务异常
			throw new RuntimeException("云效分支查询失败: " + extractErrorMessage(responseBody, e.getMessage()), e);
		} catch (Exception e) {
			log.error("云效分支查询发生未预期异常: {}", e.getMessage(), e);
			throw new RuntimeException("云效分支查询异常: " + e.getMessage(), e);
		}
	}

	/**
	 * 查询云效项目及其对应的分支列表
	 *
	 * @param queryParam 查询参数
	 * @return 包含分支列表的代码库列表
	 */
	@Override
	public List<YunxiaoRepositoryDTO> listRepositoriesWithBranches(YunxiaoRepositoryQueryParam queryParam) {
		// 获取代码库列表
		List<YunxiaoRepositoryDTO> repoList = listRepositories(queryParam);
		if (CollectionUtils.isEmpty(repoList)) {
			return Collections.emptyList();
		}

		// 循环查询每个代码库的前20个分支
		for (YunxiaoRepositoryDTO repo : repoList) {
			try {
				YunxiaoBranchQueryParam branchQueryParam = new YunxiaoBranchQueryParam();
				branchQueryParam.setPage(Constants.ONE_LONG);
				branchQueryParam.setPageSize(Constants.TWENTY_LONG);
				branchQueryParam.setSort("updated_desc");

				// 查询代码库分支列表
				List<YunxiaoBranchDTO> branchList = listRepositoryBranches(String.valueOf(repo.getId()), branchQueryParam);
				repo.setBranches(branchList);
			} catch (Exception e) {
				log.warn("获取代码库 [{}] 分支失败，已忽略分支填充: {}", repo.getName(), e.getMessage());
				repo.setBranches(Collections.emptyList());
			}
		}

		return repoList;
	}

	/**
	 * 在指定代码库中创建新分支
	 *
	 * @param createParam 创建分支参数
	 * @return 创建的分支信息
	 */
	@Override
	public YunxiaoBranchDTO createBranch(YunxiaoBranchCreateParam createParam) {
		// 校验令牌是否配置
		checkAccessToken();

		if (createParam == null) {
			throw new IllegalArgumentException("创建分支参数不能为空");
		}
		if (StringUtils.isBlank(createParam.getRepositoryId())) {
			throw new IllegalArgumentException("代码库ID不能为空");
		}
		if (StringUtils.isBlank(createParam.getBranchName())) {
			throw new IllegalArgumentException("分支名称不能为空");
		}

		String branchName = createParam.getBranchName().trim();
		String ref = StringUtils.isNotBlank(createParam.getRef()) ? createParam.getRef().trim() : DEFAULT_REF_BRANCH;
		// 解析代码库标识
		String resolvedRepoId = resolveRepositoryId(createParam.getRepositoryId());

		// 构建创建分支的 URL
		String url = buildCreateBranchUrl(resolvedRepoId, branchName, ref);
		// 创建身份认证实体
		HttpEntity<?> entity = createAuthEntity();

		try {
			log.info("开始请求云效创建分支, repoId: {}, branchName: {}, ref: {}, url: {}",
					resolvedRepoId, branchName, ref, url);

			ResponseEntity<String> response = yunxiaoRestTemplate.exchange(url, HttpMethod.POST, entity, String.class);
			String body = response.getBody();

			if (StringUtils.isBlank(body)) {
				throw new RuntimeException("云效创建分支返回为空响应");
			}

			JSONObject item = JSON.parseObject(body.trim());
			// 转换为分支 DTO
			YunxiaoBranchDTO branchDTO = convertToBranchDTO(item);
			log.info("成功创建分支: {}, 对应 commitId: {}", branchDTO.getName(), branchDTO.getCommitId());
			return branchDTO;
		} catch (HttpStatusCodeException e) {
			String responseBody = e.getResponseBodyAsString();
			log.error("云效创建分支 HTTP 异常, 状态码: {}, 响应体: {}", e.getStatusCode(), responseBody, e);
			// 提取错误提示并抛出业务异常
			throw new RuntimeException("云效创建分支失败: " + extractErrorMessage(responseBody, e.getMessage()), e);
		} catch (Exception e) {
			log.error("云效创建分支发生未预期异常: {}", e.getMessage(), e);
			throw new RuntimeException("云效创建分支异常: " + e.getMessage(), e);
		}
	}

	/**
	 * 格式化项目与分支列表为群聊展示文本
	 *
	 * @param search 搜索关键字，可为空
	 * @return 格式化后的 Markdown/文本消息
	 */
	@Override
	public String formatRepositoriesAndBranchesForChat(String search) {
		YunxiaoRepositoryQueryParam param = new YunxiaoRepositoryQueryParam();
		if (StringUtils.isNotBlank(search)) {
			param.setSearch(search.trim());
		}
		param.setPage(Constants.ONE_LONG);
		param.setPerPage(Constants.TWENTY_LONG);

		// 查询代码库及其分支列表
		List<YunxiaoRepositoryDTO> repoList = listRepositoriesWithBranches(param);

		if (CollectionUtils.isEmpty(repoList)) {
			if (StringUtils.isNotBlank(search)) {
				return "🔍 未搜索到包含关键字「" + search + "」的代码库。";
			}
			return "📂 当前组织下未查询到任何代码库。";
		}

		StringBuilder sb = new StringBuilder();
		sb.append("📋 【云效项目与分支列表】（共 ").append(repoList.size()).append(" 个项目）\n");
		sb.append("━━━━━━━━━━━━━━━━━━\n");

		for (YunxiaoRepositoryDTO repo : repoList) {
			sb.append("📦 **").append(repo.getName()).append("**");
			if (StringUtils.isNotBlank(repo.getNameWithNamespace())) {
				sb.append("（").append(repo.getNameWithNamespace()).append("）");
			}
			sb.append("\n");

			if (StringUtils.isNotBlank(repo.getWebUrl())) {
				sb.append("   🌐 页面地址：").append(repo.getWebUrl()).append("\n");
			}
			if (StringUtils.isNotBlank(repo.getHttpUrlToRepo())) {
				sb.append("   🔗 Git 克隆：`").append(repo.getHttpUrlToRepo()).append("`\n");
			}
			if (StringUtils.isNotBlank(repo.getDescription())) {
				sb.append("   📝 描述：").append(repo.getDescription()).append("\n");
			}

			List<YunxiaoBranchDTO> branches = repo.getBranches();
			if (CollectionUtils.isNotEmpty(branches)) {
				sb.append("   🌿 分支列表（前 ").append(branches.size()).append(" 个）：\n");
				for (YunxiaoBranchDTO branch : branches) {
					sb.append("      - `").append(branch.getName()).append("`");
					if (BooleanUtils.isTrue(branch.getIsProtected())) {
						sb.append(" 🔒");
					}
					if (StringUtils.isNotBlank(branch.getCommitMessage())) {
						// 截取单行且不超30字
						String msg = branch.getCommitMessage().replaceAll("[\\r\\n]+", " ").trim();
						if (msg.length() > Constants.THIRTY) {
							msg = msg.substring(Constants.ZERO, Constants.THIRTY) + "...";
						}
						sb.append(" (").append(msg).append(")");
					}
					sb.append("\n");
				}
			} else {
				sb.append("   🌿 暂无分支数据\n");
			}
			sb.append("\n");
		}

		return sb.toString().trim();
	}

	/**
	 * 解析用户群聊输入并创建分支
	 *
	 * @param userQuestion 用户提问内容
	 * @return 创建结果或引导文本
	 */
	@Override
	public String createBranchForChat(String userQuestion) {
		// 去除创建分支前缀动作词
		String content = userQuestion.replaceAll("^(创建分支|新建分支|拉分支)\\s*", "").trim();
		if (StringUtils.isBlank(content)) {
			return "💡 【创建分支指令说明】\n"
					+ "👉 指令格式：创建分支 [仓库名/默认changtai-cloud] <新分支名> [源分支/默认dev]\n"
					+ "👉 示例 1（默认项目与源分支）：创建分支 feature/order-v1\n"
					+ "👉 示例 2（指定源分支）：创建分支 feature/order-v1 dev\n"
					+ "👉 示例 3（完整参数）：创建分支 changtai-cloud feature/order-v1 dev";
		}

		String[] parts = content.split("\\s+");
		String repoIdOrName = Constants.DEFAULT_REPO_NAME;
		String newBranch = null;
		String ref = Constants.DEFAULT_DEV_BRANCH;

		// 如果用户使用 key=value 格式输入（如 仓库=xx 分支=yy 源=zz）
		if (parts.length == Constants.ONE && content.contains("=")) {
			for (String item : content.split("[;,\\s]+")) {
				if (item.startsWith("仓库=") || item.startsWith("项目=")) {
					repoIdOrName = item.substring(item.indexOf('=') + Constants.ONE);
				} else if (item.startsWith("分支=") || item.startsWith("新分支=")) {
					newBranch = item.substring(item.indexOf('=') + Constants.ONE);
				} else if (item.startsWith("源=") || item.startsWith("源分支=")) {
					ref = item.substring(item.indexOf('=') + Constants.ONE);
				}
			}
		} else if (parts.length == Constants.ONE) {
			// 单个参数：直接作为新分支名，仓库默认 changtai-cloud，源分支默认 dev
			newBranch = parts[Constants.ZERO];
		} else if (parts.length == Constants.TWO) {
			// 两个参数：若首参数包含典型分支特征，视为 <新分支名> <源分支>
			if (parts[Constants.ZERO].contains("/") || parts[Constants.ZERO].startsWith("feature")
					|| parts[Constants.ZERO].startsWith("bugfix") || parts[Constants.ZERO].startsWith("hotfix")) {
				newBranch = parts[Constants.ZERO];
				ref = parts[Constants.ONE];
			} else {
				repoIdOrName = parts[Constants.ZERO];
				newBranch = parts[Constants.ONE];
			}
		} else {
			repoIdOrName = parts[Constants.ZERO];
			newBranch = parts[Constants.ONE];
			ref = parts[Constants.TWO];
		}

		if (StringUtils.isBlank(newBranch)) {
			return "⚠️ 请提供新建分支名称！\n"
					+ "👉 格式：创建分支 [仓库名] <新分支名> [源分支]";
		}

		YunxiaoBranchCreateParam createParam = YunxiaoBranchCreateParam.builder()
				.repositoryId(repoIdOrName)
				.branchName(newBranch)
				.ref(ref)
				.build();

		try {
			// 在代码库中创建分支
			YunxiaoBranchDTO branchDTO = createBranch(createParam);
			StringBuilder sb = new StringBuilder();
			sb.append("✅ 【分支创建成功】\n");
			sb.append("━━━━━━━━━━━━━━━━━━\n");
			sb.append("📦 代码仓库：").append(repoIdOrName).append("\n");
			sb.append("🌿 新建分支：").append(branchDTO.getName()).append("\n");
			sb.append("📌 基于源分支：").append(ref).append("\n");
			if (StringUtils.isNotBlank(branchDTO.getCommitId())) {
				sb.append("🔖 最新提交：").append(branchDTO.getCommitId()).append("\n");
			}
			return sb.toString().trim();
		} catch (Exception e) {
			return "❌ 创建分支失败：" + e.getMessage();
		}
	}

	/**
	 * 查询云效流水线列表
	 *
	 * @param queryParam 查询参数
	 * @return 流水线列表
	 */
	@Override
	public List<YunxiaoPipelineDTO> listPipelines(YunxiaoPipelineQueryParam queryParam) {
		// 校验令牌是否配置
		checkAccessToken();

		// 构建流水线查询 URL
		String url = buildPipelinesUrl(queryParam);
		// 创建身份认证实体
		HttpEntity<?> entity = createAuthEntity();

		try {
			log.info("开始请求云效查询流水线列表, url: {}", url);
			ResponseEntity<String> response = yunxiaoRestTemplate.exchange(url, HttpMethod.GET, entity, String.class);
			String body = response.getBody();

			if (StringUtils.isBlank(body)) {
				log.warn("云效流水线查询返回为空响应");
				return Collections.emptyList();
			}

			// 解析响应为流水线列表
			return parseToPipelineList(body);
		} catch (HttpStatusCodeException e) {
			String responseBody = e.getResponseBodyAsString();
			log.error("云效流水线查询 HTTP 异常, 状态码: {}, 响应体: {}", e.getStatusCode(), responseBody, e);
			// 提取错误提示并抛出业务异常
			throw new RuntimeException("云效流水线查询失败: " + extractErrorMessage(responseBody, e.getMessage()), e);
		} catch (Exception e) {
			log.error("云效流水线查询发生未预期异常: {}", e.getMessage(), e);
			throw new RuntimeException("云效流水线查询异常: " + e.getMessage(), e);
		}
	}

	/**
	 * 查询云效流水线分组列表
	 *
	 * @param queryParam 查询参数
	 * @return 流水线分组列表
	 */
	@Override
	public List<YunxiaoPipelineGroupDTO> listPipelineGroups(YunxiaoPipelineGroupQueryParam queryParam) {
		// 校验令牌是否配置
		checkAccessToken();

		// 构建流水线分组查询 URL
		String url = buildPipelineGroupsUrl(queryParam);
		// 创建身份认证实体
		HttpEntity<?> entity = createAuthEntity();

		try {
			log.info("开始请求云效查询流水线分组列表, url: {}", url);
			ResponseEntity<String> response = yunxiaoRestTemplate.exchange(url, HttpMethod.GET, entity, String.class);
			String body = response.getBody();

			if (StringUtils.isBlank(body)) {
				log.warn("云效流水线分组查询返回为空响应");
				return Collections.emptyList();
			}

			// 解析响应为流水线分组列表
			List<YunxiaoPipelineGroupDTO> list = parseToPipelineGroupList(body);
			if (queryParam != null && StringUtils.isNotBlank(queryParam.getGroupName())) {
				String keyword = queryParam.getGroupName().trim();
				List<YunxiaoPipelineGroupDTO> filtered = new ArrayList<>();
				for (YunxiaoPipelineGroupDTO group : list) {
					if (StringUtils.containsIgnoreCase(group.getGroupName(), keyword)) {
						filtered.add(group);
					}
				}
				return filtered;
			}
			return list;
		} catch (HttpStatusCodeException e) {
			String responseBody = e.getResponseBodyAsString();
			log.error("云效流水线分组查询 HTTP 异常, 状态码: {}, 响应体: {}", e.getStatusCode(), responseBody, e);
			throw new RuntimeException("云效流水线分组查询失败: " + extractErrorMessage(responseBody, e.getMessage()), e);
		} catch (Exception e) {
			log.error("云效流水线分组查询发生未预期异常: {}", e.getMessage(), e);
			throw new RuntimeException("云效流水线分组查询异常: " + e.getMessage(), e);
		}
	}

	/**
	 * 格式化流水线分组列表为群聊展示文本
	 *
	 * @param userQuestion 用户在群聊中的输入
	 * @return 格式化后的 Markdown 文本
	 */
	@Override
	public String listPipelineGroupsForChat(String userQuestion) {
		// 提取可能存在的过滤关键字
		String keyword = userQuestion.replaceAll("^(流水线分组详情|流水线分组|流水线组|分组列表|查询分组|查询流水线分组|分组流水线)\\s*", "").trim();
		YunxiaoPipelineGroupQueryParam queryParam = new YunxiaoPipelineGroupQueryParam();
		if (StringUtils.isNotBlank(keyword)) {
			queryParam.setGroupName(keyword);
		}
		queryParam.setPageNum(Constants.ONE);
		queryParam.setPageSize(Constants.THIRTY);

		try {
			// 若用户提问包含详情或具体流水线诉求，输出分组及具体流水线列表
			if (userQuestion.contains("详情") || userQuestion.contains("具体") || userQuestion.contains("流水线列表")) {
				List<YunxiaoPipelineGroupDTO> groupsWithPipelines = listPipelineGroupsWithPipelines(queryParam);
				if (CollectionUtils.isEmpty(groupsWithPipelines)) {
					return "🔍 未查询到" + (StringUtils.isNotBlank(keyword) ? "与「" + keyword + "」相关的" : "") + "云效流水线分组。";
				}
				StringBuilder sb = new StringBuilder();
				sb.append("📂 【云效流水线分组及下属具体流水线】\n");
				sb.append("━━━━━━━━━━━━━━━━━━\n");
				for (YunxiaoPipelineGroupDTO g : groupsWithPipelines) {
					sb.append("📁 分组：").append(g.getGroupName()).append(" (ID: ").append(g.getGroupId()).append(")\n");
					List<YunxiaoPipelineDTO> pipelines = g.getPipelines();
					if (CollectionUtils.isEmpty(pipelines)) {
						sb.append("   └─ (暂无关联流水线)\n");
					} else {
						for (int i = Constants.ZERO; i < pipelines.size(); i++) {
							YunxiaoPipelineDTO p = pipelines.get(i);
							String prefix = (i == pipelines.size() - Constants.ONE) ? "   └─ " : "   ├─ ";
							sb.append(prefix).append(p.getPipelineName()).append(" (ID: ").append(p.getPipelineId()).append(")\n");
						}
					}
					sb.append("\n");
				}
				sb.append("━━━━━━━━━━━━━━━━━━\n");
				sb.append("💡 提示：可通过「发布分支 <模块名> <分支名> [环境]」快捷触发发布。");
				return sb.toString().trim();
			}

			List<YunxiaoPipelineGroupDTO> groups = listPipelineGroups(queryParam);
			if (CollectionUtils.isEmpty(groups)) {
				return "🔍 未查询到" + (StringUtils.isNotBlank(keyword) ? "与「" + keyword + "」相关的" : "") + "云效流水线分组。";
			}

			StringBuilder sb = new StringBuilder();
			sb.append("📂 【云效流水线分组列表】\n");
			sb.append("━━━━━━━━━━━━━━━━━━\n");
			for (int i = Constants.ZERO; i < groups.size(); i++) {
				YunxiaoPipelineGroupDTO g = groups.get(i);
				sb.append(i + Constants.ONE).append(". ")
						.append(g.getGroupName())
						.append(" (ID: ").append(g.getGroupId()).append(")\n");
			}
			sb.append("━━━━━━━━━━━━━━━━━━\n");
			sb.append("💡 提示：可通过「发布分支 分组=<分组名> 分支=<分支名>」发布指定分组分支。");
			return sb.toString().trim();
		} catch (Exception e) {
			return "❌ 查询流水线分组失败：" + e.getMessage();
		}
	}

	/**
	 * 查询云效流水线分组及其下属各具体流水线列表
	 *
	 * @param queryParam 查询参数
	 * @return 包含各分组下流水线列表的聚合信息
	 */
	@Override
	public List<YunxiaoPipelineGroupDTO> listPipelineGroupsWithPipelines(YunxiaoPipelineGroupQueryParam queryParam) {
		// 1. 先查询流水线分组列表
		List<YunxiaoPipelineGroupDTO> groups = listPipelineGroups(queryParam);
		if (CollectionUtils.isEmpty(groups)) {
			return Collections.emptyList();
		}

		// 2. 查询组织下的所有流水线列表
		YunxiaoPipelineQueryParam pipelineQuery = new YunxiaoPipelineQueryParam();
		pipelineQuery.setPageNum(Constants.ONE);
		pipelineQuery.setPageSize(Constants.HUNDRED);
		List<YunxiaoPipelineDTO> allPipelines = listPipelines(pipelineQuery);

		// 3. 将流水线按照分组进行归类聚合
		for (YunxiaoPipelineGroupDTO group : groups) {
			List<YunxiaoPipelineDTO> matchedList = new ArrayList<>();
			Long groupId = group.getGroupId();
			String groupName = group.getGroupName();

			for (YunxiaoPipelineDTO pipeline : allPipelines) {
				// 匹配方式一：按云效流水线所属 groupId 匹配
				if (pipeline.getGroupId() != null && groupId != null && pipeline.getGroupId().equals(groupId)) {
					matchedList.add(pipeline);
					continue;
				}

				// 匹配方式二：流水线名称包含该分组名称（例如 ct-modules-scm-测试环境 归类至 测试环境 分组）
				if (StringUtils.isNotBlank(groupName) && StringUtils.isNotBlank(pipeline.getPipelineName())) {
					if (pipeline.getPipelineName().contains(groupName)) {
						matchedList.add(pipeline);
					}
				}
			}
			group.setPipelines(matchedList);
		}

		return groups;
	}

	/**
	 * 触发指定分支的云效流水线发布运行
	 *
	 * @param runParam 流水线运行参数
	 * @return 流水线运行结果
	 */
	@Override
	public YunxiaoPipelineRunDTO runPipeline(YunxiaoPipelineRunParam runParam) {
		// 校验令牌是否配置
		checkAccessToken();

		if (runParam == null || StringUtils.isBlank(runParam.getBranchName())) {
			throw new IllegalArgumentException("待发布的分支名称不能为空");
		}

		String branchName = runParam.getBranchName().trim();
		String repositoryId = StringUtils.isNotBlank(runParam.getRepositoryId())
				? runParam.getRepositoryId().trim()
				: Constants.DEFAULT_REPO_NAME;

		// 解析目标发布环境（开发 dev / 测试 test / 生产 prod，或自定义环境）
		String effectiveEnv = resolveEffectiveEnv(runParam.getEnv());
		runParam.setEnv(effectiveEnv);

		// 解析发布流水线ID
		Long resolvedPipelineId = resolvePipelineId(runParam);
		if (resolvedPipelineId == null) {
			throw new IllegalStateException("未能解析到可用的发布流水线ID，请显式指定流水线ID或在配置中设置 aliyun.devops.defaultPipelineId");
		}

		// 构建流水线运行请求 URL
		String url = buildPipelineRunUrl(resolvedPipelineId);

		// 构建请求体参数
		JSONObject requestBody = buildPipelineRunBody(branchName, repositoryId, effectiveEnv, runParam.getComment(), runParam.getEnvs());
		// 创建携带令牌及 JSON 体的请求实体
		HttpEntity<?> entity = createAuthJsonEntity(requestBody);

		try {
			log.info("开始请求云效触发流水线发布, pipelineId: {}, branch: {}, env: {}, url: {}",
					resolvedPipelineId, branchName, effectiveEnv, url);
			ResponseEntity<String> response = yunxiaoRestTemplate.exchange(url, HttpMethod.POST, entity, String.class);
			String body = response.getBody();

			log.info("云效触发流水线发布响应: {}", body);
			// 解析流水线运行结果
			YunxiaoPipelineRunDTO runDTO = parsePipelineRunResponse(resolvedPipelineId, branchName, body);
			runDTO.setEnv(effectiveEnv);
			runDTO.setPipelineGroupId(runParam.getPipelineGroupId());
			runDTO.setPipelineGroupName(runParam.getPipelineGroupName());
			return runDTO;
		} catch (HttpStatusCodeException e) {
			String responseBody = e.getResponseBodyAsString();
			log.error("云效流水线触发 HTTP 异常, 状态码: {}, 响应体: {}", e.getStatusCode(), responseBody, e);
			// 提取错误提示并抛出业务异常
			throw new RuntimeException("云效流水线触发发布失败: " + extractErrorMessage(responseBody, e.getMessage()), e);
		} catch (Exception e) {
			log.error("云效流水线触发异常: {}", e.getMessage(), e);
			throw new RuntimeException("云效流水线发布异常: " + e.getMessage(), e);
		}
	}

	/**
	 * 解析钉钉群聊分支发布指令并触发流水线发布
	 *
	 * @param userQuestion 用户在群聊中的输入
	 * @return 发布结果展示 Markdown 文本
	 */
	@Override
	public String releaseBranchForChat(String userQuestion) {
		// 去除发布前缀动作词
		String content = userQuestion.replaceAll("^(发布分支|分支发布|发布代码|代码发布|发布|部署分支|分支部署|部署代码|部署)\\s*", "").trim();
		if (StringUtils.isBlank(content)) {
			return "💡 【分支发布指令说明】\n"
					+ "━━━━━━━━━━━━━━━━━━\n"
					+ "👉 指令格式：发布分支 [分组/仓库名/默认changtai-cloud] <待发布分支名> [环境/默认test] [流水线ID/可选]\n"
					+ "👉 支持环境：开发(dev)、测试(test)、生产(prod) 或 自定义环境标识（缺省默认 test 测试环境）\n\n"
					+ "👉 示例 1（默认测试环境发布）：发布分支 feature/order-v1\n"
					+ "👉 示例 2（发布到开发环境）：发布分支 feature/order-v1 开发\n"
					+ "👉 示例 3（发布到生产环境）：发布分支 feature/order-v1 生产\n"
					+ "👉 示例 4（按流水线分组发布）：发布分支 分组=营销系统 分支=feature/coupon-v1\n"
					+ "👉 示例 5（按分组与环境发布）：发布分支 营销系统 feature/coupon-v1 prod\n"
					+ "👉 示例 6（指定仓库与环境）：发布分支 changtai-cloud feature/order-v1 prod\n"
					+ "👉 示例 7（键值对完整发布）：发布分支 项目=changtai-cloud 分支=feature/order-v1 环境=测试";
		}

		String[] parts = content.split("[;,\\s]+");
		String repoIdOrName = Constants.DEFAULT_REPO_NAME;
		String branchName = null;
		Long pipelineId = null;
		String env = null;
		Long pipelineGroupId = null;
		String pipelineGroupName = null;

		List<String> remainingTokens = new ArrayList<>();
		for (String token : parts) {
			if (StringUtils.isBlank(token)) {
				continue;
			}
			String trimmedToken = token.trim();
			// 支持键值对形式（如 仓库=xx 分支=yy 环境=zz 流水线=123 分组=yy）
			if (trimmedToken.contains("=")) {
				int eqIndex = trimmedToken.indexOf('=');
				String key = trimmedToken.substring(Constants.ZERO, eqIndex).trim();
				String value = trimmedToken.substring(eqIndex + Constants.ONE).trim();
				if ("仓库".equals(key) || "项目".equals(key) || "repo".equalsIgnoreCase(key)) {
					repoIdOrName = value;
				} else if ("分支".equals(key) || "发布分支".equals(key) || "branch".equalsIgnoreCase(key)) {
					branchName = value;
				} else if ("环境".equals(key) || "env".equalsIgnoreCase(key)) {
					env = value;
				} else if ("流水线".equals(key) || "流水线ID".equals(key) || "pipelineId".equalsIgnoreCase(key)) {
					if (StringUtils.isNumeric(value)) {
						pipelineId = Long.valueOf(value);
					}
				} else if ("分组".equals(key) || "流水线分组".equals(key) || "组".equals(key) || "group".equalsIgnoreCase(key) || "groupId".equalsIgnoreCase(key)) {
					if (StringUtils.isNumeric(value)) {
						pipelineGroupId = Long.valueOf(value);
					} else {
						pipelineGroupName = value;
					}
				}
			} else if (isRecognizedEnvKeyword(trimmedToken) && env == null) {
				// 显式环境关键词 (如 开发/测试/生产/dev/test/prod/pre)
				env = trimmedToken;
			} else if (StringUtils.isNumeric(trimmedToken) && pipelineId == null) {
				// 纯数字优先作为流水线ID
				pipelineId = Long.valueOf(trimmedToken);
			} else {
				remainingTokens.add(trimmedToken);
			}
		}

		// 填充未通过 key=value 指定的分支与仓库名/分组名
		if (branchName == null) {
			if (remainingTokens.size() == Constants.ONE) {
				// 仅有1个剩余参数：作为分支名
				branchName = remainingTokens.get(Constants.ZERO);
			} else if (remainingTokens.size() >= Constants.TWO) {
				String firstToken = remainingTokens.get(Constants.ZERO);
				String secondToken = remainingTokens.get(Constants.ONE);
				// 优先检查 firstToken 是否匹配流水线分组名称
				Long matchedGroupId = resolvePipelineGroupIdByName(firstToken);
				if (matchedGroupId != null) {
					pipelineGroupId = matchedGroupId;
					pipelineGroupName = firstToken;
				} else {
					repoIdOrName = firstToken;
				}
				branchName = secondToken;
				if (env == null && remainingTokens.size() >= Constants.THREE) {
					env = remainingTokens.get(Constants.TWO);
				}
			}
		}

		if (StringUtils.isBlank(branchName)) {
			return "⚠️ 请提供待发布的分支名称！\n"
					+ "👉 格式：发布分支 [分组/仓库名] <待发布分支名> [环境]";
		}

		// 解析最终生效的环境
		String effectiveEnv = resolveEffectiveEnv(env);

		YunxiaoPipelineRunParam runParam = YunxiaoPipelineRunParam.builder()
				.repositoryId(repoIdOrName)
				.branchName(branchName)
				.env(effectiveEnv)
				.pipelineId(pipelineId)
				.pipelineGroupId(pipelineGroupId)
				.pipelineGroupName(pipelineGroupName)
				.comment("钉钉群聊触发自动化发布: " + branchName + " [" + effectiveEnv + "]")
				.build();

		try {
			// 执行流水线发布
			YunxiaoPipelineRunDTO runDTO = runPipeline(runParam);
			StringBuilder sb = new StringBuilder();
			sb.append("🚀 【分支发布流水线已触发】\n");
			sb.append("━━━━━━━━━━━━━━━━━━\n");
			if (StringUtils.isNotBlank(runDTO.getPipelineGroupName()) || runDTO.getPipelineGroupId() != null) {
				sb.append("📂 流水线分组：")
						.append(StringUtils.isNotBlank(runDTO.getPipelineGroupName()) ? runDTO.getPipelineGroupName() : "")
						.append(runDTO.getPipelineGroupId() != null ? " (ID: " + runDTO.getPipelineGroupId() + ")" : "")
						.append("\n");
			}
			sb.append("📦 代码仓库：").append(repoIdOrName).append("\n");
			sb.append("🌿 发布分支：").append(branchName).append("\n");
			sb.append("🌍 发布环境：").append(effectiveEnv).append(getEnvChineseLabel(effectiveEnv)).append("\n");
			if (runDTO.getPipelineId() != null) {
				sb.append("⚙️ 流水线ID：").append(runDTO.getPipelineId()).append("\n");
			}
			if (StringUtils.isNotBlank(runDTO.getPipelineName())) {
				sb.append("📋 流水线名称：").append(runDTO.getPipelineName()).append("\n");
			}
			if (runDTO.getPipelineRunId() != null) {
				sb.append("🆔 运行批次ID：").append(runDTO.getPipelineRunId()).append("\n");
			}
			sb.append("📊 运行状态：").append(runDTO.getStatusDescription() != null ? runDTO.getStatusDescription() : runDTO.getStatus()).append("\n");
			if (StringUtils.isNotBlank(runDTO.getTriggerTime())) {
				sb.append("⏱️ 触发时间：").append(runDTO.getTriggerTime()).append("\n");
			}
			sb.append("💡 提示：流水线已进入云效调度队列，正在执行自动化构建部署。");
			return sb.toString().trim();
		} catch (Exception e) {
			return "❌ 分支发布触发失败：" + e.getMessage();
		}
	}

	/**
	 * 终止/取消指定流水线运行实例
	 *
	 * @param cancelParam 取消参数
	 * @return 取消结果
	 */
	@Override
	public YunxiaoPipelineCancelDTO stopPipelineRun(YunxiaoPipelineCancelParam cancelParam) {
		// 校验令牌是否配置
		checkAccessToken();

		if (cancelParam == null) {
			throw new IllegalArgumentException("取消流水线运行参数不能为空");
		}

		String repositoryId = StringUtils.isNotBlank(cancelParam.getRepositoryId())
				? cancelParam.getRepositoryId().trim()
				: Constants.DEFAULT_REPO_NAME;

		// 解析目标环境
		String effectiveEnv = resolveEffectiveEnv(cancelParam.getEnv());
		cancelParam.setEnv(effectiveEnv);

		// 1. 解析流水线 ID
		Long resolvedPipelineId = cancelParam.getPipelineId();
		if (resolvedPipelineId == null || resolvedPipelineId <= Constants.ZERO_LONG) {
			YunxiaoPipelineRunParam tempParam = YunxiaoPipelineRunParam.builder()
					.repositoryId(repositoryId)
					.env(effectiveEnv)
					.pipelineGroupId(cancelParam.getPipelineGroupId())
					.pipelineGroupName(cancelParam.getPipelineGroupName())
					.build();
			// 自动匹配解析流水线ID
			resolvedPipelineId = resolvePipelineId(tempParam);
			if (tempParam.getPipelineGroupId() != null && cancelParam.getPipelineGroupId() == null) {
				cancelParam.setPipelineGroupId(tempParam.getPipelineGroupId());
			}
		}

		if (resolvedPipelineId == null || resolvedPipelineId <= Constants.ZERO_LONG) {
			throw new IllegalStateException("未能解析到目标发布流水线ID，请显式提供 pipelineId");
		}

		// 2. 解析运行批次 ID
		Long resolvedRunId = cancelParam.getPipelineRunId();
		if (resolvedRunId == null || resolvedRunId <= Constants.ZERO_LONG) {
			// 若未显式传入运行批次ID，但传入了分支名，尝试自动查找正在运行中的运行实例
			if (StringUtils.isNotBlank(cancelParam.getBranchName())) {
				resolvedRunId = findRunningPipelineRunId(resolvedPipelineId, cancelParam.getBranchName().trim());
			}
		}

		if (resolvedRunId == null || resolvedRunId <= Constants.ZERO_LONG) {
			throw new IllegalStateException("未能定位到正在运行的流水线实例批次ID，请显式提供 pipelineRunId");
		}

		// 3. 构建终止运行 URL
		String url = buildPipelineStopUrl(resolvedPipelineId, resolvedRunId);
		// 创建身份认证实体
		HttpEntity<?> entity = createAuthEntity();

		try {
			log.info("开始请求云效终止流水线运行, pipelineId: {}, pipelineRunId: {}, env: {}, url: {}",
					resolvedPipelineId, resolvedRunId, effectiveEnv, url);
			ResponseEntity<String> response = yunxiaoRestTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
			String body = response.getBody();
			log.info("云效终止流水线响应: {}", body);

			return YunxiaoPipelineCancelDTO.builder()
					.pipelineId(resolvedPipelineId)
					.pipelineRunId(resolvedRunId)
					.pipelineGroupId(cancelParam.getPipelineGroupId())
					.pipelineGroupName(cancelParam.getPipelineGroupName())
					.branchName(cancelParam.getBranchName())
					.env(effectiveEnv)
					.success(Boolean.TRUE)
					.message("已成功向云效发送终止流水线运行指令")
					.cancelTime(formatCurrentTime())
					.build();
		} catch (HttpStatusCodeException e) {
			String responseBody = e.getResponseBodyAsString();
			log.error("云效终止流水线 HTTP 异常, 状态码: {}, 响应体: {}", e.getStatusCode(), responseBody, e);
			// 提取错误提示并抛出业务异常
			throw new RuntimeException("取消流水线运行失败: " + extractErrorMessage(responseBody, e.getMessage()), e);
		} catch (Exception e) {
			log.error("云效终止流水线发生未预期异常: {}", e.getMessage(), e);
			throw new RuntimeException("取消流水线运行异常: " + e.getMessage(), e);
		}
	}

	/**
	 * 解析钉钉群聊取消分支发布指令并执行取消
	 *
	 * @param userQuestion 用户在群聊中的输入
	 * @return 取消结果展示 Markdown 文本
	 */
	@Override
	public String cancelReleaseBranchForChat(String userQuestion) {
		// 去除取消发布前缀动作词
		String content = userQuestion.replaceAll("^(取消发布分支|取消分支发布|取消发布|停止发布|中止发布|终止发布|撤销发布)\\s*", "").trim();
		if (StringUtils.isBlank(content)) {
			return "💡 【取消发布分支指令说明】\n"
					+ "━━━━━━━━━━━━━━━━━━\n"
					+ "👉 指令格式：取消发布 [分支名/或运行批次ID] [环境/默认test] [分组/仓库名/默认changtai-cloud]\n"
					+ "👉 支持环境：开发(dev)、测试(test)、生产(prod) 或 自定义环境标识（缺省默认 test 测试环境）\n\n"
					+ "👉 示例 1（按分支名取消测试环境发布）：取消发布 feature/order-v1\n"
					+ "👉 示例 2（取消开发环境发布）：取消发布 feature/order-v1 开发\n"
					+ "👉 示例 3（取消生产环境发布）：取消发布 feature/order-v1 生产\n"
					+ "👉 示例 4（按流水线分组取消）：取消发布 分组=营销系统 分支=feature/coupon-v1\n"
					+ "👉 示例 5（按运行批次ID取消）：取消发布 987654321\n"
					+ "👉 示例 6（按键值对取消）：取消发布 分支=feature/order-v1 环境=生产";
		}

		String[] parts = content.split("[;,\\s]+");
		String repoIdOrName = Constants.DEFAULT_REPO_NAME;
		String branchName = null;
		Long pipelineRunId = null;
		String env = null;
		Long pipelineGroupId = null;
		String pipelineGroupName = null;

		List<String> remainingTokens = new ArrayList<>();
		for (String token : parts) {
			if (StringUtils.isBlank(token)) {
				continue;
			}
			String trimmedToken = token.trim();
			// 支持键值对形式（如 仓库=xx 分支=yy 环境=zz 批次ID=123 分组=yy）
			if (trimmedToken.contains("=")) {
				int eqIndex = trimmedToken.indexOf('=');
				String key = trimmedToken.substring(Constants.ZERO, eqIndex).trim();
				String value = trimmedToken.substring(eqIndex + Constants.ONE).trim();
				if ("仓库".equals(key) || "项目".equals(key) || "repo".equalsIgnoreCase(key)) {
					repoIdOrName = value;
				} else if ("分支".equals(key) || "branch".equalsIgnoreCase(key)) {
					branchName = value;
				} else if ("环境".equals(key) || "env".equalsIgnoreCase(key)) {
					env = value;
				} else if ("批次".equals(key) || "批次ID".equals(key) || "runId".equalsIgnoreCase(key) || "pipelineRunId".equalsIgnoreCase(key)) {
					if (StringUtils.isNumeric(value)) {
						pipelineRunId = Long.valueOf(value);
					}
				} else if ("分组".equals(key) || "流水线分组".equals(key) || "组".equals(key) || "group".equalsIgnoreCase(key) || "groupId".equalsIgnoreCase(key)) {
					if (StringUtils.isNumeric(value)) {
						pipelineGroupId = Long.valueOf(value);
					} else {
						pipelineGroupName = value;
					}
				}
			} else if (isRecognizedEnvKeyword(trimmedToken) && env == null) {
				// 显式环境关键词 (如 开发/测试/生产/dev/test/prod/pre)
				env = trimmedToken;
			} else if (StringUtils.isNumeric(trimmedToken) && pipelineRunId == null) {
				pipelineRunId = Long.valueOf(trimmedToken);
			} else {
				remainingTokens.add(trimmedToken);
			}
		}

		// 填充未通过 key=value 指定的分支与仓库名/分组名
		if (branchName == null && pipelineRunId == null) {
			if (remainingTokens.size() == Constants.ONE) {
				branchName = remainingTokens.get(Constants.ZERO);
			} else if (remainingTokens.size() >= Constants.TWO) {
				String firstToken = remainingTokens.get(Constants.ZERO);
				String secondToken = remainingTokens.get(Constants.ONE);
				// 优先检查 firstToken 是否匹配流水线分组名称
				Long matchedGroupId = resolvePipelineGroupIdByName(firstToken);
				if (matchedGroupId != null) {
					pipelineGroupId = matchedGroupId;
					pipelineGroupName = firstToken;
				} else {
					repoIdOrName = firstToken;
				}
				branchName = secondToken;
				if (env == null && remainingTokens.size() >= Constants.THREE) {
					env = remainingTokens.get(Constants.TWO);
				}
			}
		} else if (branchName == null && !remainingTokens.isEmpty()) {
			branchName = remainingTokens.get(Constants.ZERO);
		}

		// 解析生效的环境
		String effectiveEnv = resolveEffectiveEnv(env);

		YunxiaoPipelineCancelParam cancelParam = YunxiaoPipelineCancelParam.builder()
				.repositoryId(repoIdOrName)
				.branchName(branchName)
				.pipelineRunId(pipelineRunId)
				.pipelineGroupId(pipelineGroupId)
				.pipelineGroupName(pipelineGroupName)
				.env(effectiveEnv)
				.build();

		try {
			// 执行终止流水线运行
			YunxiaoPipelineCancelDTO cancelDTO = stopPipelineRun(cancelParam);
			StringBuilder sb = new StringBuilder();
			sb.append("🛑 【分支发布已成功取消】\n");
			sb.append("━━━━━━━━━━━━━━━━━━\n");
			if (StringUtils.isNotBlank(cancelDTO.getPipelineGroupName()) || cancelDTO.getPipelineGroupId() != null) {
				sb.append("📂 流水线分组：")
						.append(StringUtils.isNotBlank(cancelDTO.getPipelineGroupName()) ? cancelDTO.getPipelineGroupName() : "")
						.append(cancelDTO.getPipelineGroupId() != null ? " (ID: " + cancelDTO.getPipelineGroupId() + ")" : "")
						.append("\n");
			}
			sb.append("📦 代码仓库：").append(repoIdOrName).append("\n");
			if (StringUtils.isNotBlank(cancelDTO.getBranchName())) {
				sb.append("🌿 取消分支：").append(cancelDTO.getBranchName()).append("\n");
			}
			sb.append("🌍 所属环境：").append(effectiveEnv).append(getEnvChineseLabel(effectiveEnv)).append("\n");
			if (cancelDTO.getPipelineId() != null) {
				sb.append("⚙️ 流水线ID：").append(cancelDTO.getPipelineId()).append("\n");
			}
			if (cancelDTO.getPipelineRunId() != null) {
				sb.append("🆔 终止批次ID：").append(cancelDTO.getPipelineRunId()).append("\n");
			}
			if (StringUtils.isNotBlank(cancelDTO.getCancelTime())) {
				sb.append("⏱️ 取消时间：").append(cancelDTO.getCancelTime()).append("\n");
			}
			sb.append("💡 提示：云效流水线已成功接收终止指令，发布任务已中断。");
			return sb.toString().trim();
		} catch (Exception e) {
			return "❌ 取消分支发布失败：" + e.getMessage();
		}
	}

	/**
	 * 解析最终生效的环境编码或自定义名称
	 *
	 * @param inputEnv 用户输入的环境参数
	 * @return 规范化的环境名称
	 */
	private String resolveEffectiveEnv(String inputEnv) {
		if (StringUtils.isNotBlank(inputEnv)) {
			// 智能解析环境标识（支持中文别名与自定义环境名称）
			return ReleaseEnvironmentEnum.resolveEnvCode(inputEnv.trim());
		}
		if (StringUtils.isNotBlank(yunxiaoProperties.getDefaultEnv())) {
			return ReleaseEnvironmentEnum.resolveEnvCode(yunxiaoProperties.getDefaultEnv().trim());
		}
		return Constants.DEFAULT_ENV;
	}

	/**
	 * 判断是否为内置环境识别关键词
	 *
	 * @param token 词段
	 * @return 是否为环境关键词
	 */
	private boolean isRecognizedEnvKeyword(String token) {
		if (StringUtils.isBlank(token)) {
			return false;
		}
		String lower = token.trim().toLowerCase();
		return "dev".equals(lower) || "test".equals(lower) || "prod".equals(lower) || "pre".equals(lower)
				|| "开发".equals(token) || "开发环境".equals(token)
				|| "测试".equals(token) || "测试环境".equals(token)
				|| "生产".equals(token) || "生产环境".equals(token)
				|| "预发".equals(token) || "预发布".equals(token);
	}

	/**
	 * 获取环境对应的中文补充标签
	 *
	 * @param envCode 环境编码
	 * @return 中文标签
	 */
	private String getEnvChineseLabel(String envCode) {
		ReleaseEnvironmentEnum envEnum = ReleaseEnvironmentEnum.getByCode(envCode);
		if (envEnum != null) {
			return "（" + envEnum.getDescription() + "）";
		}
		return "（自定义环境）";
	}

	/**
	 * 根据分支名称查找正在运行或等待中的流水线运行批次 ID
	 *
	 * @param pipelineId 流水线ID
	 * @param branchName 分支名称
	 * @return 运行批次ID，若未找到返回 null
	 */
	private Long findRunningPipelineRunId(Long pipelineId, String branchName) {
		String baseEndpoint = getBaseEndpoint();
		StringBuilder pathBuilder = new StringBuilder(baseEndpoint).append(Constants.FLOW_BASE_PATH);

		if (StringUtils.isNotBlank(yunxiaoProperties.getOrganizationId())) {
			pathBuilder.append("/organizations/").append(yunxiaoProperties.getOrganizationId().trim());
		}
		pathBuilder.append("/pipelines/").append(pipelineId).append("/runs");

		String url = UriComponentsBuilder.fromHttpUrl(pathBuilder.toString())
				.queryParam("statusList", "RUNNING,WAITING")
				.queryParam("page", Constants.ONE_LONG)
				.queryParam("perPage", Constants.TEN_LONG)
				.toUriString();

		HttpEntity<?> entity = createAuthEntity();
		try {
			log.info("查询正在运行中的流水线实例, url: {}", url);
			ResponseEntity<String> response = yunxiaoRestTemplate.exchange(url, HttpMethod.GET, entity, String.class);
			String body = response.getBody();
			if (StringUtils.isBlank(body)) {
				return null;
			}

			// 解析运行实例列表
			JSONArray array = parseToJsonArray(body);
			if (array == null && StringUtils.isNotBlank(body)) {
				JSONObject obj = JSON.parseObject(body.trim());
				if (obj.containsKey("runs")) {
					array = obj.getJSONArray("runs");
				} else if (obj.containsKey("pipelineRuns")) {
					array = obj.getJSONArray("pipelineRuns");
				}
			}

			if (array == null || array.isEmpty()) {
				return null;
			}

			for (int i = Constants.ZERO; i < array.size(); i++) {
				JSONObject runItem = array.getJSONObject(i);
				Long runId = runItem.getLong("pipelineRunId") != null ? runItem.getLong("pipelineRunId") : runItem.getLong("id");
				// 匹配分支
				JSONArray branchModes = runItem.getJSONArray("branchModeBranchs");
				if (branchModes != null && branchModes.contains(branchName)) {
					log.info("匹配到运行中实例 pipelineRunId: {}, 分支: {}", runId, branchName);
					return runId;
				}
				// 尝试匹配通用 branch 属性
				String itemBranch = runItem.getString("branch");
				if (StringUtils.equalsIgnoreCase(itemBranch, branchName)) {
					log.info("匹配到运行中实例 pipelineRunId: {}, 分支: {}", runId, branchName);
					return runId;
				}
			}

			// 若未严格匹配到分支，但仅有一个正在运行中的实例，则默认返回该实例
			if (array.size() == Constants.ONE) {
				JSONObject single = array.getJSONObject(Constants.ZERO);
				Long runId = single.getLong("pipelineRunId") != null ? single.getLong("pipelineRunId") : single.getLong("id");
				log.info("当前流水线仅有一个运行中实例，自动选用 pipelineRunId: {}", runId);
				return runId;
			}
		} catch (Exception e) {
			log.warn("查询运行中流水线实例异常: {}", e.getMessage());
		}

		return null;
	}

	/**
	 * 构建流水线终止运行请求 URL
	 *
	 * @param pipelineId    流水线ID
	 * @param pipelineRunId 运行批次ID
	 * @return URL 字符串
	 */
	private String buildPipelineStopUrl(Long pipelineId, Long pipelineRunId) {
		String baseEndpoint = getBaseEndpoint();
		StringBuilder pathBuilder = new StringBuilder(baseEndpoint).append(Constants.FLOW_BASE_PATH);

		if (StringUtils.isNotBlank(yunxiaoProperties.getOrganizationId())) {
			pathBuilder.append("/organizations/").append(yunxiaoProperties.getOrganizationId().trim());
		}
		pathBuilder.append("/pipelines/").append(pipelineId)
				.append("/pipelineRuns/").append(pipelineRunId).append("/stop");

		return pathBuilder.toString();
	}

	/**
	 * 解析目标发布流水线 ID（结合仓库与目标环境智能匹配）
	 *
	 * @param runParam 运行参数
	 * @return 流水线 ID
	 */
	private Long resolvePipelineId(YunxiaoPipelineRunParam runParam) {
		// 1. 如果入参显式指定了流水线ID，直接使用
		if (runParam != null && runParam.getPipelineId() != null && runParam.getPipelineId() > Constants.ZERO_LONG) {
			return runParam.getPipelineId();
		}

		// 2. 检查配置中的默认流水线ID
		if (yunxiaoProperties.getDefaultPipelineId() != null && yunxiaoProperties.getDefaultPipelineId() > Constants.ZERO_LONG) {
			return yunxiaoProperties.getDefaultPipelineId();
		}

		// 3. 检查是否指定了流水线分组进行匹配
		Long targetGroupId = runParam != null ? runParam.getPipelineGroupId() : null;
		if (targetGroupId == null && runParam != null && StringUtils.isNotBlank(runParam.getPipelineGroupName())) {
			targetGroupId = resolvePipelineGroupIdByName(runParam.getPipelineGroupName());
			if (targetGroupId != null) {
				runParam.setPipelineGroupId(targetGroupId);
			}
		}

		String repoTarget = (runParam != null && StringUtils.isNotBlank(runParam.getRepositoryId()))
				? runParam.getRepositoryId().trim()
				: Constants.DEFAULT_REPO_NAME;
		String envTarget = (runParam != null && StringUtils.isNotBlank(runParam.getEnv()))
				? runParam.getEnv().trim()
				: resolveEffectiveEnv(null);

		// 如果指定了流水线分组，优先在该分组内匹配流水线
		if (targetGroupId != null && targetGroupId > Constants.ZERO_LONG) {
			YunxiaoPipelineQueryParam groupPipelineQuery = new YunxiaoPipelineQueryParam();
			groupPipelineQuery.setPipelineGroupId(targetGroupId);
			groupPipelineQuery.setPageNum(Constants.ONE);
			groupPipelineQuery.setPageSize(Constants.THIRTY);
			List<YunxiaoPipelineDTO> groupPipelines = listPipelines(groupPipelineQuery);
			if (CollectionUtils.isNotEmpty(groupPipelines)) {
				List<YunxiaoPipelineDTO> filtered = new ArrayList<>();
				for (YunxiaoPipelineDTO p : groupPipelines) {
					if (p.getGroupId() == null || p.getGroupId().equals(targetGroupId)) {
						filtered.add(p);
					}
				}
				if (CollectionUtils.isEmpty(filtered)) {
					filtered = groupPipelines;
				}

				// 3.1 优先匹配同时包含仓库与环境名称的流水线
				for (YunxiaoPipelineDTO pipeline : filtered) {
					String pName = pipeline.getPipelineName();
					if (StringUtils.containsIgnoreCase(pName, repoTarget) && isPipelineMatchEnv(pName, envTarget)) {
						log.info("分组 [{}] 内按仓库与环境精确匹配到流水线 [{}] ID: {}, 环境: {}", targetGroupId, pName, pipeline.getPipelineId(), envTarget);
						return pipeline.getPipelineId();
					}
				}

				// 3.2 其次匹配包含环境名称的流水线
				for (YunxiaoPipelineDTO pipeline : filtered) {
					if (isPipelineMatchEnv(pipeline.getPipelineName(), envTarget)) {
						log.info("分组 [{}] 内按环境匹配到流水线 [{}] ID: {}, 环境: {}", targetGroupId, pipeline.getPipelineName(), pipeline.getPipelineId(), envTarget);
						return pipeline.getPipelineId();
					}
				}

				// 3.3 再次匹配包含仓库名称的流水线
				for (YunxiaoPipelineDTO pipeline : filtered) {
					if (StringUtils.containsIgnoreCase(pipeline.getPipelineName(), repoTarget)) {
						log.info("分组 [{}] 内按仓库匹配到流水线 [{}] ID: {}", targetGroupId, pipeline.getPipelineName(), pipeline.getPipelineId());
						return pipeline.getPipelineId();
					}
				}

				// 3.4 若仅有1个或未细分环境，使用分组下首个流水线
				YunxiaoPipelineDTO first = filtered.get(Constants.ZERO);
				log.info("分组 [{}] 内选用首个流水线 [{}] ID: {}", targetGroupId, first.getPipelineName(), first.getPipelineId());
				return first.getPipelineId();
			}
		}

		String searchTarget = StringUtils.isNotBlank(yunxiaoProperties.getDefaultPipelineName())
				? yunxiaoProperties.getDefaultPipelineName().trim()
				: repoTarget;

		if (StringUtils.isNotBlank(searchTarget)) {
			YunxiaoPipelineQueryParam queryParam = new YunxiaoPipelineQueryParam();
			queryParam.setPipelineName(searchTarget);
			queryParam.setPageNum(Constants.ONE);
			queryParam.setPageSize(Constants.TEN);

			// 查询云效流水线列表
			List<YunxiaoPipelineDTO> pipelines = listPipelines(queryParam);
			if (CollectionUtils.isNotEmpty(pipelines)) {
				// 优先同时包含仓库与环境名称的流水线 (如 changtai-cloud-test 或 ct-modules-scm-测试环境)
				for (YunxiaoPipelineDTO pipeline : pipelines) {
					String pName = pipeline.getPipelineName();
					if (StringUtils.containsIgnoreCase(pName, searchTarget) && isPipelineMatchEnv(pName, envTarget)) {
						log.info("按仓库与环境精确匹配到发布流水线 [{}] ID: {}, 环境: {}", pName, pipeline.getPipelineId(), envTarget);
						return pipeline.getPipelineId();
					}
				}

				// 其次优先完全匹配名称
				for (YunxiaoPipelineDTO pipeline : pipelines) {
					if (StringUtils.equalsIgnoreCase(pipeline.getPipelineName(), searchTarget)) {
						log.info("精确匹配到发布流水线 [{}] ID: {}", pipeline.getPipelineName(), pipeline.getPipelineId());
						return pipeline.getPipelineId();
					}
				}

				// 再次取首个模糊匹配结果
				YunxiaoPipelineDTO first = pipelines.get(Constants.ZERO);
				log.info("未精确匹配，使用首个流水线搜索结果 [{}] ID: {}", first.getPipelineName(), first.getPipelineId());
				return first.getPipelineId();
			}
		}

		return null;
	}

	/**
	 * 构建流水线列表请求 URL
	 *
	 * @param queryParam 查询参数
	 * @return URL 字符串
	 */
	private String buildPipelinesUrl(YunxiaoPipelineQueryParam queryParam) {
		// 获取云效基础请求端点
		String baseEndpoint = getBaseEndpoint();
		StringBuilder pathBuilder = new StringBuilder(baseEndpoint).append(Constants.FLOW_BASE_PATH);

		if (StringUtils.isNotBlank(yunxiaoProperties.getOrganizationId())) {
			pathBuilder.append("/organizations/").append(yunxiaoProperties.getOrganizationId().trim());
		}
		pathBuilder.append("/pipelines");

		long page = (queryParam != null && queryParam.getPageNum() != null)
				? queryParam.getPageNum().longValue()
				: Constants.ONE_LONG;
		long pageSize = (queryParam != null && queryParam.getPageSize() != null)
				? queryParam.getPageSize().longValue()
				: Constants.TWENTY_LONG;

		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(pathBuilder.toString())
				.queryParam("page", page)
				.queryParam("perPage", pageSize);

		if (queryParam != null && StringUtils.isNotBlank(queryParam.getPipelineName())) {
			builder.queryParam("pipelineName", queryParam.getPipelineName().trim());
		}

		if (queryParam != null && queryParam.getPipelineGroupId() != null) {
			builder.queryParam("pipelineGroupId", queryParam.getPipelineGroupId());
		}

		return builder.toUriString();
	}

	/**
	 * 构建流水线触发运行请求 URL
	 *
	 * @param pipelineId 流水线ID
	 * @return URL 字符串
	 */
	private String buildPipelineRunUrl(Long pipelineId) {
		// 获取云效基础请求端点
		String baseEndpoint = getBaseEndpoint();
		StringBuilder pathBuilder = new StringBuilder(baseEndpoint).append(Constants.FLOW_BASE_PATH);

		if (StringUtils.isNotBlank(yunxiaoProperties.getOrganizationId())) {
			pathBuilder.append("/organizations/").append(yunxiaoProperties.getOrganizationId().trim());
		}
		pathBuilder.append("/pipelines/").append(pipelineId).append("/run");

		return pathBuilder.toString();
	}

	/**
	 * 构建云效流水线运行的请求体 JSON
	 *
	 * @param branchName   分支名称
	 * @param repositoryId 代码库标识
	 * @param env          目标环境
	 * @param comment      运行备注说明
	 * @param envs         自定义环境变量
	 * @return JSONObject 请求体
	 */
	private JSONObject buildPipelineRunBody(String branchName, String repositoryId, String env, String comment, Map<String, String> envs) {
		JSONObject body = new JSONObject();
		JSONArray branchArray = new JSONArray();
		branchArray.add(branchName);

		JSONObject paramsObj = new JSONObject();
		paramsObj.put("branchModeBranchs", branchArray);
		if (StringUtils.isNotBlank(comment)) {
			paramsObj.put("comment", comment);
		} else {
			paramsObj.put("comment", "触发分支自动化发布: " + branchName + " [" + env + "]");
		}

		JSONObject envMap = new JSONObject();
		envMap.put("branch", branchName);
		envMap.put("env", env);
		envMap.put("BUILD_ENV", env);
		if (envs != null && !envs.isEmpty()) {
			envMap.putAll(envs);
		}
		paramsObj.put("envs", envMap);

		// 云效 Flow 支持在 params 字段中放置 JSON 字符串，也支持直接放置字段
		body.put("params", paramsObj.toJSONString());
		body.put("branchModeBranchs", branchArray);
		body.put("envs", envMap);
		body.put("comment", paramsObj.getString("comment"));

		return body;
	}

	/**
	 * 解析流水线运行接口响应为 DTO
	 *
	 * @param pipelineId 流水线ID
	 * @param branchName 分支名称
	 * @param body       响应体
	 * @return YunxiaoPipelineRunDTO
	 */
	private YunxiaoPipelineRunDTO parsePipelineRunResponse(Long pipelineId, String branchName, String body) {
		Long runId = null;
		String status = PipelineRunStatusEnum.RUNNING.getCode();

		if (StringUtils.isNotBlank(body)) {
			try {
				JSONObject obj = JSON.parseObject(body.trim());
				if (obj.containsKey("pipelineRunId")) {
					runId = obj.getLong("pipelineRunId");
				} else if (obj.containsKey("id")) {
					runId = obj.getLong("id");
				} else if (obj.containsKey("data")) {
					Object dataObj = obj.get("data");
					if (dataObj instanceof JSONObject) {
						JSONObject dataJson = (JSONObject) dataObj;
						runId = dataJson.getLong("pipelineRunId") != null ? dataJson.getLong("pipelineRunId") : dataJson.getLong("id");
					} else if (dataObj instanceof Number) {
						runId = ((Number) dataObj).longValue();
					}
				}

				if (obj.containsKey("status")) {
					status = obj.getString("status");
				}
			} catch (Exception e) {
				log.warn("解析流水线运行响应异常: {}", e.getMessage());
			}
		}

		// 根据状态编码解析枚举
		PipelineRunStatusEnum statusEnum = PipelineRunStatusEnum.getByCode(status);
		String statusDesc = statusEnum != null ? statusEnum.getDescription() : status;

		// 格式化当前触发时间
		String triggerTime = formatCurrentTime();

		return YunxiaoPipelineRunDTO.builder()
				.pipelineId(pipelineId)
				.pipelineRunId(runId)
				.branchName(branchName)
				.status(status)
				.statusDescription(statusDesc)
				.triggerTime(triggerTime)
				.build();
	}

	/**
	 * 格式化当前系统时间为标准字符串
	 *
	 * @return 格式化后的时间字符串
	 */
	private String formatCurrentTime() {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
	}

	/**
	 * 解析响应字符串为流水线列表
	 *
	 * @param body 响应体
	 * @return 流水线列表
	 */
	private List<YunxiaoPipelineDTO> parseToPipelineList(String body) {
		// 先尝试解析为直接数组
		JSONArray array = parseToJsonArray(body);
		if (array == null && StringUtils.isNotBlank(body)) {
			JSONObject obj = JSON.parseObject(body.trim());
			if (obj.containsKey("pipelines")) {
				array = obj.getJSONArray("pipelines");
			} else if (obj.containsKey("pipelineList")) {
				array = obj.getJSONArray("pipelineList");
			}
		}

		if (array == null || array.isEmpty()) {
			return Collections.emptyList();
		}

		List<YunxiaoPipelineDTO> list = new ArrayList<>(array.size());
		for (int i = Constants.ZERO; i < array.size(); i++) {
			JSONObject item = array.getJSONObject(i);
			// 转换为流水线 DTO
			list.add(convertToPipelineDTO(item));
		}
		return list;
	}

	/**
	 * 将流水线 JSON 转换为 DTO
	 *
	 * @param item JSON 对象
	 * @return YunxiaoPipelineDTO
	 */
	private YunxiaoPipelineDTO convertToPipelineDTO(JSONObject item) {
		Long pId = item.getLong("pipelineId") != null ? item.getLong("pipelineId") : item.getLong("id");
		String name = item.getString("pipelineName") != null ? item.getString("pipelineName") : item.getString("name");
		Long gId = item.getLong("groupId") != null ? item.getLong("groupId") : item.getLong("pipelineGroupId");

		return YunxiaoPipelineDTO.builder()
				.pipelineId(pId)
				.pipelineName(name)
				.groupId(gId)
				.creatorAccountId(item.getString("creatorAccountId"))
				.envName(item.getString("envName"))
				.createTime(item.getString("gmtCreate"))
				.build();
	}

	/**
	 * 解析代码库标识，如果是名称则自动匹配对应数字ID
	 *
	 * @param repositoryIdOrName 代码库ID或名称
	 * @return 代码库数字ID
	 */
	private String resolveRepositoryId(String repositoryIdOrName) {
		String trimmed = repositoryIdOrName.trim();
		// 如果已经是纯数字 ID，直接返回
		if (StringUtils.isNumeric(trimmed)) {
			return trimmed;
		}

		// 按名称搜索匹配仓库 ID
		YunxiaoRepositoryQueryParam queryParam = new YunxiaoRepositoryQueryParam();
		queryParam.setSearch(trimmed);
		queryParam.setPage(Constants.ONE_LONG);
		queryParam.setPerPage(Constants.TWENTY_LONG);

		// 查询匹配的代码库列表
		List<YunxiaoRepositoryDTO> repos = listRepositories(queryParam);
		if (CollectionUtils.isNotEmpty(repos)) {
			for (YunxiaoRepositoryDTO repo : repos) {
				if (StringUtils.equalsIgnoreCase(repo.getName(), trimmed)
						|| StringUtils.equalsIgnoreCase(repo.getPath(), trimmed)
						|| (repo.getNameWithNamespace() != null && repo.getNameWithNamespace().endsWith(trimmed))) {
					log.info("成功将代码库名称 [{}] 解析为 ID: {}", trimmed, repo.getId());
					return String.valueOf(repo.getId());
				}
			}
			// 若没有完全匹配，取第一个模糊匹配结果
			YunxiaoRepositoryDTO first = repos.get(Constants.ZERO);
			log.info("未精确匹配到名称，使用首个搜索结果 [{}] ID: {}", first.getName(), first.getId());
			return String.valueOf(first.getId());
		}

		return trimmed;
	}

	/**
	 * 构建创建分支的 URL
	 *
	 * @param repositoryId 代码库ID
	 * @param branchName   分支名称
	 * @param ref          源分支
	 * @return URL 字符串
	 */
	private String buildCreateBranchUrl(String repositoryId, String branchName, String ref) {
		// 获取云效基础请求端点
		String baseEndpoint = getBaseEndpoint();
		StringBuilder pathBuilder = new StringBuilder(baseEndpoint).append(CODEUP_BASE_PATH);

		if (StringUtils.isNotBlank(yunxiaoProperties.getOrganizationId())) {
			pathBuilder.append("/organizations/").append(yunxiaoProperties.getOrganizationId().trim());
		}
		pathBuilder.append("/repositories/").append(repositoryId.trim()).append("/branches");

		return UriComponentsBuilder.fromHttpUrl(pathBuilder.toString())
				.queryParam("branch", branchName)
				.queryParam("ref", ref)
				.toUriString();
	}

	/**
	 * 校验云效个人访问令牌是否已配置
	 */
	private void checkAccessToken() {
		if (StringUtils.isBlank(yunxiaoProperties.getAccessToken())) {
			log.error("未配置云效个人访问令牌 (aliyun.devops.accessToken)");
			throw new IllegalStateException("未配置云效个人访问令牌 (aliyun.devops.accessToken)，请在 application.properties 中配置");
		}
	}

	/**
	 * 创建携带 x-yunxiao-token 的 HTTP 请求实体
	 *
	 * @return HttpEntity
	 */
	private HttpEntity<?> createAuthEntity() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(YUNXIAO_TOKEN_HEADER, yunxiaoProperties.getAccessToken().trim());
		headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
		return new HttpEntity<>(headers);
	}

	/**
	 * 创建携带 x-yunxiao-token 及 JSON 请求体的 HTTP 实体
	 *
	 * @param body 请求体对象
	 * @return HttpEntity
	 */
	private HttpEntity<?> createAuthJsonEntity(Object body) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(YUNXIAO_TOKEN_HEADER, yunxiaoProperties.getAccessToken().trim());
		headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
		return new HttpEntity<>(body, headers);
	}

	/**
	 * 构建代码库列表请求 URL
	 *
	 * @param queryParam 查询参数
	 * @return URL 字符串
	 */
	private String buildRepositoriesUrl(YunxiaoRepositoryQueryParam queryParam) {
		// 获取云效基础请求端点
		String baseEndpoint = getBaseEndpoint();
		StringBuilder pathBuilder = new StringBuilder(baseEndpoint).append(CODEUP_BASE_PATH);

		if (StringUtils.isNotBlank(yunxiaoProperties.getOrganizationId())) {
			pathBuilder.append("/organizations/").append(yunxiaoProperties.getOrganizationId().trim());
		}
		pathBuilder.append("/repositories");

		long page = (queryParam != null && queryParam.getPage() != null) ? queryParam.getPage() : Constants.ONE_LONG;
		long perPage = (queryParam != null && queryParam.getPerPage() != null) ? queryParam.getPerPage() : Constants.TWENTY_LONG;

		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(pathBuilder.toString())
				.queryParam("page", page)
				.queryParam("perPage", perPage);

		if (queryParam != null && StringUtils.isNotBlank(queryParam.getSearch())) {
			builder.queryParam("search", queryParam.getSearch().trim());
		}

		return builder.toUriString();
	}

	/**
	 * 构建分支列表请求 URL
	 *
	 * @param repositoryId 代码库ID
	 * @param queryParam   查询参数
	 * @return URL 字符串
	 */
	private String buildBranchesUrl(String repositoryId, YunxiaoBranchQueryParam queryParam) {
		// 获取云效基础请求端点
		String baseEndpoint = getBaseEndpoint();
		StringBuilder pathBuilder = new StringBuilder(baseEndpoint).append(CODEUP_BASE_PATH);

		if (StringUtils.isNotBlank(yunxiaoProperties.getOrganizationId())) {
			pathBuilder.append("/organizations/").append(yunxiaoProperties.getOrganizationId().trim());
		}
		pathBuilder.append("/repositories/").append(repositoryId.trim()).append("/branches");

		long page = (queryParam != null && queryParam.getPage() != null) ? queryParam.getPage() : Constants.ONE_LONG;
		long pageSize = (queryParam != null && queryParam.getPageSize() != null) ? queryParam.getPageSize() : Constants.TWENTY_LONG;

		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(pathBuilder.toString())
				.queryParam("page", page)
				.queryParam("pageSize", pageSize);

		if (queryParam != null) {
			if (StringUtils.isNotBlank(queryParam.getSearch())) {
				builder.queryParam("search", queryParam.getSearch().trim());
			}
			if (StringUtils.isNotBlank(queryParam.getSort())) {
				builder.queryParam("sort", queryParam.getSort().trim());
			}
		}

		return builder.toUriString();
	}

	/**
	 * 获取带协议的前缀域名
	 *
	 * @return 基础 URL
	 */
	private String getBaseEndpoint() {
		String endpoint = yunxiaoProperties.getEndpoint();
		if (StringUtils.isBlank(endpoint)) {
			endpoint = "openapi-rdc.aliyuncs.com";
		}
		if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
			endpoint = "https://" + endpoint;
		}
		return endpoint;
	}

	/**
	 * 解析云效响应为 JSONArray
	 *
	 * @param body 响应体字符串
	 * @return JSONArray
	 */
	private JSONArray parseToJsonArray(String body) {
		String trimmed = body.trim();
		if (trimmed.startsWith("[")) {
			return JSON.parseArray(trimmed);
		} else if (trimmed.startsWith("{")) {
			JSONObject obj = JSON.parseObject(trimmed);
			if (obj.containsKey("result")) {
				return obj.getJSONArray("result");
			}
			if (obj.containsKey("data")) {
				return obj.getJSONArray("data");
			}
		}
		return null;
	}

	/**
	 * 提取错误响应信息
	 *
	 * @param responseBody 响应体
	 * @param defaultMsg   默认错误信息
	 * @return 友好错误提示
	 */
	private String extractErrorMessage(String responseBody, String defaultMsg) {
		if (StringUtils.isBlank(responseBody)) {
			return defaultMsg;
		}
		try {
			JSONObject obj = JSON.parseObject(responseBody.trim());
			if (obj.containsKey("errorMessage")) {
				return obj.getString("errorMessage");
			}
			if (obj.containsKey("message")) {
				return obj.getString("message");
			}
		} catch (Exception ignored) {
		}
		return responseBody;
	}

	/**
	 * 将代码库 JSON 对象转换为 DTO
	 *
	 * @param item JSON 对象
	 * @return DTO
	 */
	private YunxiaoRepositoryDTO convertToRepositoryDTO(JSONObject item) {
		return YunxiaoRepositoryDTO.builder()
				.id(item.getLong("id"))
				.name(item.getString("name"))
				.nameWithNamespace(item.getString("nameWithNamespace"))
				.path(item.getString("path"))
				.pathWithNamespace(item.getString("pathWithNamespace"))
				.webUrl(item.getString("webUrl"))
				.httpUrlToRepo(item.getString("httpUrlToRepo"))
				.sshUrlToRepo(item.getString("sshUrlToRepo"))
				.description(item.getString("description"))
				.visibilityLevel(item.getString("visibility"))
				.lastActivityAt(item.getString("lastActivityAt"))
				.build();
	}

	/**
	 * 将分支 JSON 对象转换为 DTO
	 *
	 * @param item JSON 对象
	 * @return DTO
	 */
	private YunxiaoBranchDTO convertToBranchDTO(JSONObject item) {
		YunxiaoBranchDTO.YunxiaoBranchDTOBuilder builder = YunxiaoBranchDTO.builder()
				.name(item.getString("name"))
				.isProtected(item.getBoolean("protected"));

		JSONObject commit = item.getJSONObject("commit");
		if (commit != null) {
			builder.commitId(commit.getString("id"))
					.commitMessage(commit.getString("message"))
					.commitAuthorName(commit.getString("authorName"))
					.commitAuthorEmail(commit.getString("authorEmail"))
					.committedDate(commit.getString("committedDate"));
		}

		return builder.build();
	}

	/**
	 * 判断流水线名称是否匹配目标环境（同时支持英文code和中文描述，如 test 与 测试环境）
	 *
	 * @param pipelineName 流水线名称
	 * @param envCode      环境编码
	 * @return 是否匹配
	 */
	private boolean isPipelineMatchEnv(String pipelineName, String envCode) {
		if (StringUtils.isBlank(pipelineName) || StringUtils.isBlank(envCode)) {
			return false;
		}
		if (StringUtils.containsIgnoreCase(pipelineName, envCode)) {
			return true;
		}
		ReleaseEnvironmentEnum envEnum = ReleaseEnvironmentEnum.getByCode(envCode);
		if (envEnum != null && StringUtils.containsIgnoreCase(pipelineName, envEnum.getDescription())) {
			return true;
		}
		// 针对“测试”、“开发”、“生产”、“预发布”简写兼容
		if ("test".equalsIgnoreCase(envCode) && pipelineName.contains("测试")) {
			return true;
		}
		if ("dev".equalsIgnoreCase(envCode) && pipelineName.contains("开发")) {
			return true;
		}
		if ("prod".equalsIgnoreCase(envCode) && pipelineName.contains("生产")) {
			return true;
		}
		if ("pre".equalsIgnoreCase(envCode) && (pipelineName.contains("预发") || pipelineName.contains("预发布"))) {
			return true;
		}
		return false;
	}

	/**
	 * 根据分组名称解析流水线分组 ID
	 *
	 * @param groupName 分组名称
	 * @return 分组 ID，若未找到返回 null
	 */
	private Long resolvePipelineGroupIdByName(String groupName) {
		if (StringUtils.isBlank(groupName)) {
			return null;
		}
		try {
			YunxiaoPipelineGroupQueryParam queryParam = new YunxiaoPipelineGroupQueryParam();
			queryParam.setGroupName(groupName.trim());
			queryParam.setPageNum(Constants.ONE);
			queryParam.setPageSize(Constants.THIRTY);
			List<YunxiaoPipelineGroupDTO> list = listPipelineGroups(queryParam);
			if (CollectionUtils.isEmpty(list)) {
				return null;
			}
			// 优先完全匹配
			for (YunxiaoPipelineGroupDTO g : list) {
				if (StringUtils.equalsIgnoreCase(g.getGroupName(), groupName.trim())) {
					return g.getGroupId();
				}
			}
			// 模糊匹配首个
			return list.get(Constants.ZERO).getGroupId();
		} catch (Exception e) {
			log.warn("根据名称解析流水线分组失败, groupName: {}, 异常: {}", groupName, e.getMessage());
			return null;
		}
	}

	/**
	 * 构建流水线分组列表请求 URL
	 *
	 * @param queryParam 查询参数
	 * @return URL 字符串
	 */
	private String buildPipelineGroupsUrl(YunxiaoPipelineGroupQueryParam queryParam) {
		String baseEndpoint = getBaseEndpoint();
		StringBuilder pathBuilder = new StringBuilder(baseEndpoint).append(Constants.FLOW_BASE_PATH);

		if (StringUtils.isNotBlank(yunxiaoProperties.getOrganizationId())) {
			pathBuilder.append("/organizations/").append(yunxiaoProperties.getOrganizationId().trim());
		}
		pathBuilder.append("/pipelineGroups");

		long page = (queryParam != null && queryParam.getPageNum() != null)
				? queryParam.getPageNum().longValue()
				: Constants.ONE_LONG;
		long pageSize = (queryParam != null && queryParam.getPageSize() != null)
				? queryParam.getPageSize().longValue()
				: Constants.THIRTY_LONG;

		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(pathBuilder.toString())
				.queryParam("page", page)
				.queryParam("perPage", pageSize);

		return builder.toUriString();
	}

	/**
	 * 解析响应字符串为流水线分组列表
	 *
	 * @param body 响应体
	 * @return 流水线分组列表
	 */
	private List<YunxiaoPipelineGroupDTO> parseToPipelineGroupList(String body) {
		JSONArray array = parseToJsonArray(body);
		if (array == null && StringUtils.isNotBlank(body)) {
			JSONObject obj = JSON.parseObject(body.trim());
			if (obj.containsKey("pipelineGroups")) {
				array = obj.getJSONArray("pipelineGroups");
			} else if (obj.containsKey("pipelineGroupList")) {
				array = obj.getJSONArray("pipelineGroupList");
			} else if (obj.containsKey("data")) {
				array = obj.getJSONArray("data");
			}
		}

		if (array == null || array.isEmpty()) {
			return Collections.emptyList();
		}

		List<YunxiaoPipelineGroupDTO> list = new ArrayList<>(array.size());
		for (int i = Constants.ZERO; i < array.size(); i++) {
			JSONObject item = array.getJSONObject(i);
			list.add(convertToPipelineGroupDTO(item));
		}
		return list;
	}

	/**
	 * 将流水线分组 JSON 转换为 DTO
	 *
	 * @param item JSON 对象
	 * @return YunxiaoPipelineGroupDTO
	 */
	private YunxiaoPipelineGroupDTO convertToPipelineGroupDTO(JSONObject item) {
		Long id = item.getLong("id") != null ? item.getLong("id") : item.getLong("groupId");
		String name = item.getString("name") != null ? item.getString("name") : item.getString("groupName");

		return YunxiaoPipelineGroupDTO.builder()
				.groupId(id)
				.groupName(name)
				.creatorAccountId(item.getString("creatorAccountId"))
				.createTime(item.getString("gmtCreate"))
				.build();
	}
}
