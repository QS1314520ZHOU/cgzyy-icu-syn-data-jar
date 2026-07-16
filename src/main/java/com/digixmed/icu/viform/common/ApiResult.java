package com.digixmed.icu.viform.common;

import lombok.Data;

/**
 * 统一 API 返回体。
 *
 * <p>所有 Controller 接口统一使用此结构返回，便于平台方解析。</p>
 *
 * <pre>
 * { "msg": "操作成功", "data": true, "status": 100 }
 * </pre>
 */
@Data
public class ApiResult {

    /** 提示消息 */
    private String msg;

    /** 返回数据（可 null 表示无附加数据） */
    private Object data;

    /** 状态码：100=成功，其它为业务错误码 */
    private int status;

    private ApiResult(String msg, Object data, int status) {
        this.msg = msg;
        this.data = data;
        this.status = status;
    }

    // ==================== 工厂方法 ====================

    /** 操作成功 */
    public static ApiResult success(String msg) {
        return new ApiResult(msg, true, 100);
    }

    /** 操作成功（默认消息） */
    public static ApiResult success() {
        return success("操作成功");
    }

    /** 操作失败 */
    public static ApiResult fail(String msg, int status) {
        return new ApiResult(msg, false, status);
    }

    /** 操作失败（默认 status=-1） */
    public static ApiResult fail(String msg) {
        return fail(msg, -1);
    }

    // ==================== 业务状态码 ====================

    /** 患者不存在 */
    public static final int PATIENT_NOT_FOUND = 200;
    /** 未知操作类型 */
    public static final int UNKNOWN_OPERATION_TYPE = 201;
    /** 参数校验失败 */
    public static final int PARAM_INVALID = 202;
    /** 系统内部错误 */
    public static final int INTERNAL_ERROR = 500;
}
