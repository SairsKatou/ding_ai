package org.example.util;

import com.ct.common.core.constant.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 源码轻量解析工具：识别方法声明并定位方法体范围（基于大括号匹配，不做完整语法分析）
 *
 * @author zeymo
 */
public final class JavaSourceParser {

    /**
     * 方法声明行中方法名之前不应出现的字符（排除赋值、调用、return 等语句）
     */
    private static final Pattern NON_DECLARATION_PREFIX = Pattern.compile(
            "[=.(!:+\"]|->|&&|\\|\\||//|^\\*|\\b(?:return|new|throw|if|while|for|switch|catch|else)\\b");

    /**
     * 与方法写在同一行的注解，如 @GetMapping("/list")
     */
    private static final Pattern INLINE_ANNOTATION = Pattern.compile("@\\w+(?:\\s*\\([^)]*\\))?");

    /**
     * 形如 name( 的候选声明
     */
    private static final Pattern NAME_BEFORE_PAREN = Pattern.compile("(?<![\\w$.@])([A-Za-z_$][\\w$]*)\\s*\\(");

    /**
     * 声明前缀必须以类型结尾（标识符、泛型 >、数组 ]）
     */
    private static final Pattern TYPE_SUFFIX = Pattern.compile("[\\w$>\\]]$");

    private JavaSourceParser() {
    }

    /**
     * 方法范围（行下标从 0 开始，含首尾）
     *
     * @param name            方法名
     * @param startLine       含注解与注释的起始行
     * @param declarationLine 声明所在行
     * @param endLine         方法结束行
     */
    public record MethodRange(String name, int startLine, int declarationLine, int endLine) {

        public boolean contains(int line) {
            return line >= startLine && line <= endLine;
        }
    }

    /**
     * 列出文件中的顶层方法（嵌套在方法体内的匿名类方法归属外层方法）
     *
     * @param lines 源码行
     * @return 按行号顺序排列的方法范围
     */
    public static List<MethodRange> listMethods(String[] lines) {
        List<MethodRange> methods = new ArrayList<>();
        for (int i = Constants.ZERO; i < lines.length; i++) {
            String name = findDeclaredName(lines[i]);
            if (name == null) {
                continue;
            }
            int end = findMethodEnd(lines, i);
            methods.add(new MethodRange(name, findDeclarationStart(lines, i), i, end));
            i = end;
        }
        return methods;
    }

    /**
     * 识别行内声明的方法名，非声明行返回 null
     */
    private static String findDeclaredName(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("@")) {
            return null;
        }
        Matcher matcher = NAME_BEFORE_PAREN.matcher(line);
        while (matcher.find()) {
            String prefix = line.substring(Constants.ZERO, matcher.start());
            String declarationPrefix = INLINE_ANNOTATION.matcher(prefix).replaceAll("").trim();
            if (isMethodDeclaration(prefix) && TYPE_SUFFIX.matcher(declarationPrefix).find()) {
                return matcher.group(Constants.ONE);
            }
        }
        return null;
    }

    /**
     * 根据方法名之前的内容判断是否是声明（而非调用、赋值、return 语句或注释）
     *
     * @param prefix 方法名之前的文本
     * @return 是否为方法声明
     */
    public static boolean isMethodDeclaration(String prefix) {
        String declarationPrefix = INLINE_ANNOTATION.matcher(prefix).replaceAll("").trim();
        return !declarationPrefix.isEmpty() && !NON_DECLARATION_PREFIX.matcher(declarationPrefix).find();
    }

    /**
     * 向上包含方法的注解与注释
     *
     * @param lines           源码行
     * @param declarationLine 声明所在行
     * @return 起始行
     */
    public static int findDeclarationStart(String[] lines, int declarationLine) {
        int start = declarationLine;
        for (int i = declarationLine - Constants.ONE; i >= Constants.ZERO && declarationLine - i <= Constants.THIRTY; i--) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.endsWith(";") || line.endsWith("{") || line.endsWith("}")) {
                break;
            }
            start = i;
        }
        return start;
    }

    /**
     * 从方法声明行开始匹配大括号，返回方法结束行（跳过字符串、字符与注释中的括号）
     *
     * @param lines           源码行
     * @param declarationLine 声明所在行
     * @return 结束行
     */
    public static int findMethodEnd(String[] lines, int declarationLine) {
        int depth = Constants.ZERO;
        boolean bodyStarted = false;
        boolean inBlockComment = false;
        for (int i = declarationLine; i < lines.length; i++) {
            String line = lines[i];
            for (int j = Constants.ZERO; j < line.length(); j++) {
                char c = line.charAt(j);
                if (inBlockComment) {
                    if (c == '*' && j + Constants.ONE < line.length() && line.charAt(j + Constants.ONE) == '/') {
                        inBlockComment = false;
                        j++;
                    }
                } else if (c == '/' && j + Constants.ONE < line.length() && line.charAt(j + Constants.ONE) == '/') {
                    break;
                } else if (c == '/' && j + Constants.ONE < line.length() && line.charAt(j + Constants.ONE) == '*') {
                    inBlockComment = true;
                    j++;
                } else if (c == '"' || c == '\'') {
                    j = skipQuoted(line, j, c);
                } else if (c == ';' && !bodyStarted) {
                    // 接口 / 抽象方法没有方法体
                    return i;
                } else if (c == '{') {
                    depth++;
                    bodyStarted = true;
                } else if (c == '}') {
                    depth--;
                    if (bodyStarted && depth == Constants.ZERO) {
                        return i;
                    }
                }
            }
        }
        return lines.length - Constants.ONE;
    }

    /**
     * 跳过字符串或字符字面量，返回结束引号下标
     */
    private static int skipQuoted(String line, int start, char quote) {
        for (int i = start + Constants.ONE; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        return line.length();
    }
}
