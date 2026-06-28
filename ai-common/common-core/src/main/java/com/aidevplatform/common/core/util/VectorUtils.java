package com.aidevplatform.common.core.util;

/**
 * 向量工具类，统一处理 float[] 与 pgvector 字符串格式互转。
 * pgvector 要求格式：[0.1,0.2,0.3]
 */
public final class VectorUtils {

    private VectorUtils() {}

    /**
     * float[] → pgvector 字符串，如 [0.1,0.2,0.3]。
     *
     * @param vector embedding 向量，不能为 null 或空数组
     * @return pgvector 可接受的字符串
     * @throws IllegalArgumentException vector 为 null 或空时抛出
     */
    public static String toStr(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("向量不能为空");
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        return sb.append("]").toString();
    }

    /**
     * pgvector 字符串 → float[]，如 "[0.1,0.2,0.3]" → float[]{0.1f,0.2f,0.3f}。
     *
     * @param vectorStr pgvector 格式字符串
     * @return float 数组
     * @throws IllegalArgumentException 字符串为空时抛出
     */
    public static float[] fromStr(String vectorStr) {
        if (vectorStr == null || vectorStr.isBlank()) {
            throw new IllegalArgumentException("向量字符串不能为空");
        }
        String trimmed = vectorStr.trim();
        if (trimmed.startsWith("[")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        String[] parts = trimmed.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
