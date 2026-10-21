package com.ct.common.core.web.domain;

import com.ct.common.core.constant.Constants;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 响应信息主体
 *
 * @param <T> 业务数据泛型
 * @author zeymo
 */
@Data
@ApiModel(description = "统一响应结果")
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 响应业务状态码
     */
    @ApiModelProperty(value = "状态码", example = "200")
    private Integer code;

    /**
     * 响应信息
     */
    @ApiModelProperty(value = "提示信息", example = "操作成功")
    private String msg;

    /**
     * 业务数据
     */
    @ApiModelProperty(value = "返回数据")
    private T data;

    public Result() {
    }

    public Result(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    /**
     * 判断是否成功
     *
     * @return 是否成功
     */
    public boolean isSuccess() {
        return Constants.SUCCESS.equals(this.code);
    }

    /**
     * 成功返回
     *
     * @param <T> 数据类型
     * @return 结果
     */
    public static <T> Result<T> success() {
        return new Result<>(Constants.SUCCESS, "操作成功", null);
    }

    /**
     * 成功返回
     *
     * @param data 数据
     * @param <T>  数据类型
     * @return 结果
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(Constants.SUCCESS, "操作成功", data);
    }

    /**
     * 成功返回
     *
     * @param msg  消息
     * @param data 数据
     * @param <T>  数据类型
     * @return 结果
     */
    public static <T> Result<T> success(String msg, T data) {
        return new Result<>(Constants.SUCCESS, msg, data);
    }

    /**
     * 操作失败返回
     *
     * @param msg 消息
     * @param <T> 数据类型
     * @return 结果
     */
    public static <T> Result<T> operationFailed(String msg) {
        return new Result<>(Constants.FAIL, msg, null);
    }

    /**
     * 操作失败返回
     *
     * @param msg  消息
     * @param data 数据
     * @param <T>  数据类型
     * @return 结果
     */
    public static <T> Result<T> operationFailed(String msg, T data) {
        return new Result<>(Constants.FAIL, msg, data);
    }

    /**
     * 系统错误返回
     *
     * @param <T> 数据类型
     * @return 结果
     */
    public static <T> Result<T> systemError() {
        return new Result<>(Constants.FAIL, "系统异常，请稍后再试", null);
    }
}
