package org.example.controller;

import com.ct.common.core.web.domain.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.example.domain.dto.YunxiaoCodeScanParam;
import org.example.domain.dto.YunxiaoCodeScanResultDTO;
import org.example.service.YunxiaoCodeScanService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 云效分支代码大模型扫描与梳理接口
 *
 * @author zeymo
 */
@Api(tags = "云效分支代码大模型扫描与分析")
@RestController
@RequestMapping("/api/yunxiao/code")
public class YunxiaoCodeScanController {

    @Resource
    private YunxiaoCodeScanService yunxiaoCodeScanService;

    @ApiOperation(value = "扫描指定分支代码并通过大模型分析业务诉求")
    @PostMapping("/scan-and-analyze")
    public Result<YunxiaoCodeScanResultDTO> scanAndAnalyze(@Validated @RequestBody YunxiaoCodeScanParam param) {
        // 调用大模型代码扫描与梳理服务
        YunxiaoCodeScanResultDTO resultDTO = yunxiaoCodeScanService.scanAndAnalyzeBranch(param);
        return Result.success(resultDTO);
    }
}
