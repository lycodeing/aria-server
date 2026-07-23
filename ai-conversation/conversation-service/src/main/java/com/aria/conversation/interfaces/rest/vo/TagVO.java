package com.aria.conversation.interfaces.rest.vo;

/**
 * 标签视图对象。
 *
 * @param id     标签 ID
 * @param name   标签名称
 * @param color  标签颜色（十六进制色值，如 #FF5733）
 * @param source 来源：PRESET（预置）| CUSTOM（自定义）
 */
public record TagVO(Long id, String name, String color, String source) {}
