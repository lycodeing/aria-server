package com.aria.customerservice.auth.interfaces.rest.vo;

import java.util.List;

/**
 * 通用分页结果 VO，用于所有返回列表+总数的接口。
 */
public record PageVO<T>(List<T> list, long total) {}
