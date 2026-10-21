package org.example.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ct.common.core.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.example.config.YunxiaoProperties;
import org.example.domain.dto.YunxiaoBranchCreateParam;
import org.example.domain.dto.YunxiaoBranchDTO;
import org.example.domain.dto.YunxiaoBranchQueryParam;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 云效 DevOps 代码库与分支管理实现类（通过个人访问令牌）
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
        checkAccessToken();

        String url = buildRepositoriesUrl(queryParam);
        HttpEntity<?> entity = createAuthEntity();

        try {
            log.info("开始请求云效查询代码库列表, url: {}", url);
            ResponseEntity<String> response = yunxiaoRestTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String body = response.getBody();

            if (StringUtils.isBlank(body)) {
                log.warn("云效代码库查询返回为空响应");
                return Collections.emptyList();
            }

            JSONArray jsonArray = parseToJsonArray(body);
            if (jsonArray == null || jsonArray.isEmpty()) {
                log.info("未获取到云效代码库数据");
                return Collections.emptyList();
            }

            List<YunxiaoRepositoryDTO> list = new ArrayList<>(jsonArray.size());
            for (int i = Constants.ZERO; i < jsonArray.size(); i++) {
                JSONObject item = jsonArray.getJSONObject(i);
                list.add(convertToRepositoryDTO(item));
            }

            log.info("成功获取云效代码库列表, 数量: {}", list.size());
            return list;
        } catch (Exception e) {
            log.error("调用云效获取代码库列表失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取云效代码库列表异常: " + e.getMessage(), e);
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
        if (StringUtils.isBlank(repositoryId)) {
            throw new IllegalArgumentException("代码库 ID 不能为空");
        }
        checkAccessToken();

        String resolvedRepoId = resolveRepositoryId(repositoryId);
        String url = buildBranchesUrl(resolvedRepoId, queryParam);
        HttpEntity<?> entity = createAuthEntity();

        try {
            log.info("开始请求云效查询代码库 [{}] 的分支列表, url: {}", resolvedRepoId, url);
            ResponseEntity<String> response = yunxiaoRestTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String body = response.getBody();

            if (StringUtils.isBlank(body)) {
                log.info("代码库 {} 未查询到分支数据", resolvedRepoId);
                return Collections.emptyList();
            }

            JSONArray jsonArray = parseToJsonArray(body);
            if (jsonArray == null || jsonArray.isEmpty()) {
                return Collections.emptyList();
            }

            List<YunxiaoBranchDTO> branchList = new ArrayList<>(jsonArray.size());
            for (int i = Constants.ZERO; i < jsonArray.size(); i++) {
                JSONObject item = jsonArray.getJSONObject(i);
                branchList.add(convertToBranchDTO(item));
            }

            log.info("成功获取代码库 {} 的分支列表, 数量: {}", resolvedRepoId, branchList.size());
            return branchList;
        } catch (Exception e) {
            log.error("调用云效获取代码库 [{}] 分支失败: {}", resolvedRepoId, e.getMessage(), e);
            throw new RuntimeException("获取云效分支列表异常: " + e.getMessage(), e);
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
        List<YunxiaoRepositoryDTO> repoList = listRepositories(queryParam);
        if (CollectionUtils.isEmpty(repoList)) {
            return Collections.emptyList();
        }

        YunxiaoBranchQueryParam branchQuery = new YunxiaoBranchQueryParam();
        branchQuery.setPage(Constants.ONE_LONG);
        branchQuery.setPageSize(Constants.TWENTY_LONG);

        for (YunxiaoRepositoryDTO repo : repoList) {
            if (repo.getId() != null) {
                try {
                    List<YunxiaoBranchDTO> branches = listRepositoryBranches(String.valueOf(repo.getId()), branchQuery);
                    repo.setBranches(branches);
                } catch (Exception e) {
                    log.warn("获取代码库 [{}] 分支失败, 忽略单仓库异常: {}", repo.getName(), e.getMessage());
                    repo.setBranches(Collections.emptyList());
                }
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
        if (createParam == null) {
            throw new IllegalArgumentException("创建分支参数不能为空");
        }
        if (StringUtils.isBlank(createParam.getRepositoryId())) {
            throw new IllegalArgumentException("代码库 ID 或代码库名称不能为空");
        }
        if (StringUtils.isBlank(createParam.getBranchName())) {
            throw new IllegalArgumentException("新建分支名称不能为空");
        }
        checkAccessToken();

        String resolvedRepoId = resolveRepositoryId(createParam.getRepositoryId());
        String ref = StringUtils.isNotBlank(createParam.getRef()) ? createParam.getRef().trim() : DEFAULT_REF_BRANCH;
        String branchName = createParam.getBranchName().trim();

        String url = buildCreateBranchUrl(resolvedRepoId, branchName, ref);
        HttpEntity<?> entity = createAuthEntity();

        try {
            log.info("开始请求云效创建分支, repoId: {}, branchName: {}, ref: {}, url: {}",
                    resolvedRepoId, branchName, ref, url);

            ResponseEntity<String> response = yunxiaoRestTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            String body = response.getBody();

            if (StringUtils.isBlank(body)) {
                throw new RuntimeException("云效创建分支返回为空响应");
            }

            JSONObject obj = JSON.parseObject(body.trim());
            if (obj.containsKey("result")) {
                obj = obj.getJSONObject("result");
            }

            YunxiaoBranchDTO branchDTO = convertToBranchDTO(obj);
            log.info("成功在代码库 [{}] 创建分支 [{}]", resolvedRepoId, branchName);
            return branchDTO;
        } catch (HttpStatusCodeException e) {
            String responseBody = e.getResponseBodyAsString();
            log.error("调用云效创建分支 HTTP 错误, 状态码: {}, 响应体: {}", e.getStatusCode(), responseBody, e);
            String errorMsg = extractErrorMessage(responseBody, e.getMessage());
            throw new RuntimeException("云效创建分支失败: " + errorMsg, e);
        } catch (Exception e) {
            log.error("调用云效创建分支异常: {}", e.getMessage(), e);
            throw new RuntimeException("创建分支异常: " + e.getMessage(), e);
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
        param.setSearch(search);
        param.setPage(Constants.ONE_LONG);
        param.setPerPage(Constants.TWENTY_LONG);

        List<YunxiaoRepositoryDTO> repoList;
        try {
            repoList = listRepositoriesWithBranches(param);
        } catch (Exception e) {
            return "❌ 获取云效项目与分支列表失败: " + e.getMessage() + "\n请检查 aliyun.devops.accessToken 是否有效。";
        }

        if (CollectionUtils.isEmpty(repoList)) {
            if (StringUtils.isNotBlank(search)) {
                return "🔍 未查询到包含关键字「" + search + "」的云效代码库。";
            }
            return "🔍 暂未查询到任何云效代码库。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 【云效项目与分支列表】（共 ").append(repoList.size()).append(" 个项目）\n");
        sb.append("━━━━━━━━━━━━━━━━━━\n");

        int index = Constants.ONE;
        for (YunxiaoRepositoryDTO repo : repoList) {
            sb.append(index++).append(". 📦 仓库名称：").append(repo.getName());
            if (StringUtils.isNotBlank(repo.getNameWithNamespace())) {
                sb.append(" (").append(repo.getNameWithNamespace()).append(")");
            }
            sb.append("\n");

            if (StringUtils.isNotBlank(repo.getWebUrl())) {
                sb.append("   🔗 地址：").append(repo.getWebUrl()).append("\n");
            }

            List<YunxiaoBranchDTO> branches = repo.getBranches();
            if (CollectionUtils.isEmpty(branches)) {
                sb.append("   🌿 分支：暂无分支\n");
            } else {
                sb.append("   🌿 分支列表（共 ").append(branches.size()).append(" 个）：\n");
                for (YunxiaoBranchDTO branch : branches) {
                    sb.append("      ▪ ").append(branch.getName());
                    if (BooleanUtils.isTrue(branch.getIsProtected())) {
                        sb.append(" [保护]");
                    }
                    if (StringUtils.isNotBlank(branch.getCommitMessage())) {
                        sb.append(" - ").append(branch.getCommitMessage().trim());
                    }
                    sb.append("\n");
                }
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
        // 去除“创建分支”前缀
        String content = userQuestion.replaceAll("^(创建分支|新建分支|拉分支)\\s*", "").trim();
        if (StringUtils.isBlank(content)) {
            return "💡 【创建分支指令说明】\n"
                    + "👉 指令格式：创建分支 <代码库名或ID> <新分支名> [源分支/默认master]\n"
                    + "👉 示例 1：创建分支 changtai-cloud feature/order-v1\n"
                    + "👉 示例 2：创建分支 changtai-cloud feature/order-v1 develop";
        }

        // 支持空格分隔：<repo> <branch> [ref]
        String[] parts = content.split("\\s+");
        String repoIdOrName = parts[0];
        String newBranch = parts.length > 1 ? parts[1] : null;
        String ref = parts.length > 2 ? parts[2] : DEFAULT_REF_BRANCH;

        // 如果用户使用 key=value 格式输入（如 仓库=xx 分支=yy 源=zz）
        if (parts.length == 1 && content.contains("=")) {
            for (String item : content.split("[;,\\s]+")) {
                if (item.startsWith("仓库=") || item.startsWith("项目=")) {
                    repoIdOrName = item.substring(item.indexOf('=') + 1);
                } else if (item.startsWith("分支=") || item.startsWith("新分支=")) {
                    newBranch = item.substring(item.indexOf('=') + 1);
                } else if (item.startsWith("源=") || item.startsWith("源分支=")) {
                    ref = item.substring(item.indexOf('=') + 1);
                }
            }
        }

        if (StringUtils.isBlank(newBranch)) {
            return "⚠️ 请提供新建分支名称！\n"
                    + "👉 格式：创建分支 " + repoIdOrName + " <新分支名> [源分支]";
        }

        YunxiaoBranchCreateParam createParam = YunxiaoBranchCreateParam.builder()
                .repositoryId(repoIdOrName)
                .branchName(newBranch)
                .ref(ref)
                .build();

        try {
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
            YunxiaoRepositoryDTO first = repos.get(0);
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
     * 构建代码库列表请求 URL
     *
     * @param queryParam 查询参数
     * @return URL 字符串
     */
    private String buildRepositoriesUrl(YunxiaoRepositoryQueryParam queryParam) {
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
}
