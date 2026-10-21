package org.example.controller;

import com.ct.common.core.web.domain.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.example.domain.dto.OpenChatRequestDTO;
import org.example.service.OpenCodeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * OpenCode 大模型接口控制器
 *
 * @author zeymo
 */
@Api(tags = "OpenCode大模型服务")
@RestController
@RequestMapping("/api/ai")
public class OpenCodeController {

    @Resource
    private OpenCodeService openCodeService;

    @ApiOperation(value = "单轮AI对话提问")
    @PostMapping("/chat")
    public Result<String> chat(@RequestParam("message") String message) {
        try {
            // 调用大模型单轮对话接口
            String reply = openCodeService.chat(message);
            return Result.success(reply);
        } catch (Exception e) {
            // 捕获多模型重试全部失败等异常，返回业务错误
            return Result.operationFailed(e.getMessage());
        }
    }

    @ApiOperation(value = "多轮AI对话提问")
    @PostMapping("/chat-completions")
    public Result<String> chatCompletions(@RequestBody OpenChatRequestDTO requestDTO) {
        try {
            // 调用大模型多轮对话接口
            String reply = openCodeService.chat(requestDTO != null ? requestDTO.getMessages() : null);
            return Result.success(reply);
        } catch (Exception e) {
            // 捕获多模型重试全部失败等异常，返回业务错误
            return Result.operationFailed(e.getMessage());
        }
    }
}
