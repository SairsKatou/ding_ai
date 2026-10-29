package org.example.util;

import com.ct.common.core.constant.Constants;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 代码检索 Token 匹配工具：英文 Token 按标识符词边界匹配（驼峰、下划线、非字母数字），中文 Token 按子串匹配
 * <p>
 * 例如 log 可命中 log.info、operation_log、OperationLog，但不会命中 catalog、dialog、logistics。
 *
 * @author zeymo
 */
public final class CodeTokenMatcher {

    /**
     * 英文 Token 结尾允许的屈折后缀，如 detail -> details、add -> added
     */
    private static final List<String> INFLECTION_SUFFIXES = Arrays.asList("s", "es", "ed", "ing", "er");

    private CodeTokenMatcher() {
    }

    /**
     * 统计 Token 在文本中按词边界命中的次数
     *
     * @param text  原始文本（保留大小写，用于识别驼峰边界）
     * @param token 小写 Token
     * @return 命中次数
     */
    public static int countMatches(String text, String token) {
        return StringUtils.isEmpty(text) ? Constants.ZERO : countMatches(text, text.toLowerCase(), token);
    }

    /**
     * 统计 Token 在文本中按词边界命中的次数（调用方已预先转小写，批量匹配多个 Token 时避免重复转换）
     *
     * @param text      原始文本
     * @param lowerText 原始文本的小写形式
     * @param token     小写 Token
     * @return 命中次数
     */
    public static int countMatches(String text, String lowerText, String token) {
        if (StringUtils.isEmpty(text) || StringUtils.isEmpty(token)) {
            return Constants.ZERO;
        }
        // 个别字符转小写后长度会变化（如 İ），下标无法对齐原文时退化为子串匹配
        boolean boundaryCheck = isAsciiToken(token) && lowerText.length() == text.length();
        int count = Constants.ZERO;
        int index = lowerText.indexOf(token);
        while (index >= Constants.ZERO) {
            if (!boundaryCheck || isBoundaryMatch(text, index, token.length())) {
                count++;
            }
            index = lowerText.indexOf(token, index + token.length());
        }
        return count;
    }

    /**
     * 判断 Token 是否按词边界出现在文本中
     *
     * @param text  原始文本
     * @param token 小写 Token
     * @return 是否命中
     */
    public static boolean contains(String text, String token) {
        if (StringUtils.isEmpty(text) || StringUtils.isEmpty(token)) {
            return false;
        }
        String lowerText = text.toLowerCase();
        if (!isAsciiToken(token) || lowerText.length() != text.length()) {
            return lowerText.contains(token);
        }
        int index = lowerText.indexOf(token);
        while (index >= Constants.ZERO) {
            if (isBoundaryMatch(text, index, token.length())) {
                return true;
            }
            index = lowerText.indexOf(token, index + Constants.ONE);
        }
        return false;
    }

    private static boolean isAsciiToken(String token) {
        for (int i = Constants.ZERO; i < token.length(); i++) {
            if (token.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBoundaryMatch(String text, int start, int length) {
        if (!isStartBoundary(text, start)) {
            return false;
        }
        int end = start + length;
        if (isEndBoundary(text, end)) {
            return true;
        }
        // 允许常见屈折后缀：detail(s)、add(ed)、split(ing)
        String rest = text.substring(end).toLowerCase();
        for (String suffix : INFLECTION_SUFFIXES) {
            if (rest.startsWith(suffix) && isEndBoundary(text, end + suffix.length())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 起始边界：文本开头、前一个字符非 ASCII 字母数字，或驼峰 / 数字切换处
     */
    private static boolean isStartBoundary(String text, int start) {
        if (start == Constants.ZERO) {
            return true;
        }
        char prev = text.charAt(start - Constants.ONE);
        char cur = text.charAt(start);
        if (!isAsciiAlnum(prev)) {
            return true;
        }
        if (Character.isDigit(prev) != Character.isDigit(cur)) {
            return true;
        }
        if (Character.isLowerCase(prev) && Character.isUpperCase(cur)) {
            return true;
        }
        // 连续大写缩写后接新单词：SCMOrder 中的 Order
        return Character.isUpperCase(prev) && Character.isUpperCase(cur)
                && start + Constants.ONE < text.length() && Character.isLowerCase(text.charAt(start + Constants.ONE));
    }

    /**
     * 结束边界：文本结尾、后一个字符非 ASCII 字母数字，或驼峰 / 数字切换处
     */
    private static boolean isEndBoundary(String text, int end) {
        if (end >= text.length()) {
            return true;
        }
        char last = text.charAt(end - Constants.ONE);
        char next = text.charAt(end);
        if (!isAsciiAlnum(next)) {
            return true;
        }
        if (Character.isDigit(last) != Character.isDigit(next)) {
            return true;
        }
        if (Character.isUpperCase(next) && !Character.isUpperCase(last)) {
            return true;
        }
        // 连续大写缩写后接新单词：SCMOrder 中的 SCM
        return Character.isUpperCase(last) && Character.isUpperCase(next)
                && end + Constants.ONE < text.length() && Character.isLowerCase(text.charAt(end + Constants.ONE));
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
