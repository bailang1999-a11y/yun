package com.xiyiyun.shop.it.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 支持 DELIMITER 的 SQL 脚本执行器。
 *
 * <p>为什么不调用 mysql 客户端（scripts/schema-check.mjs 的做法）：
 * 已实测 backend 容器（maven:3.9.9-eclipse-temurin-21）内**没有 mysql / mysqladmin 客户端**，
 * 而所有测试按约束必须在该容器里跑，容器内也没有 docker CLI 可以去 exec mysql 容器。
 * 因此这里只能走纯 JDBC，自己实现一个 DELIMITER 感知的切分器。
 *
 * <p>DELIMITER 是 mysql 客户端的指令，不是 SQL 语句，服务端不认识它，
 * 所以它纯粹是「客户端如何切句子」的问题：解析器读到 {@code DELIMITER $$} 之后，
 * 改用 {@code $$} 作为语句终止符，于是 {@code CREATE PROCEDURE ... BEGIN ... END} 内部的
 * 分号不再被当成语句边界，整个存储过程体会作为一条语句交给 JDBC。
 *
 * <p>切分器同时正确处理：单引号 / 双引号 / 反引号字符串（含反斜杠转义与 '' 双写转义）、
 * {@code --} 与 {@code #} 行注释、{@code /* *}{@code /} 块注释。注释不会被误当作语句内容切开，
 * 但会原样保留在语句文本里（MySQL 能接受）。
 */
public final class SqlScriptRunner {

    private SqlScriptRunner() {
    }

    /** 逐条执行脚本；任何一条失败都抛出，附带出错语句的前 300 字符，便于定位。 */
    public static void execute(Connection connection, String scriptName, String script) throws SQLException {
        List<String> statements = split(script);
        int index = 0;
        for (String sql : statements) {
            index++;
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException ex) {
                throw new SQLException(
                    "执行 " + scriptName + " 第 " + index + " 条语句失败: " + ex.getMessage()
                        + "\n---- 语句片段 ----\n" + abbreviate(sql),
                    ex.getSQLState(), ex.getErrorCode(), ex);
            }
        }
    }

    private static String abbreviate(String sql) {
        String trimmed = sql.strip();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + " ...";
    }

    /**
     * 按当前 delimiter 把脚本切成可执行语句列表。空语句被丢弃。
     */
    public static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String delimiter = ";";
        int i = 0;
        int length = script.length();
        boolean atLineStart = true;

        while (i < length) {
            char c = script.charAt(i);

            // 行首（忽略空白）识别 DELIMITER 指令
            if (atLineStart && (c == 'D' || c == 'd') && matchesIgnoreCase(script, i, "delimiter")) {
                int afterKeyword = i + "delimiter".length();
                if (afterKeyword < length && (script.charAt(afterKeyword) == ' ' || script.charAt(afterKeyword) == '\t')) {
                    // 先把 DELIMITER 之前累积的内容作为一条语句收尾
                    addIfNotBlank(statements, current);
                    int cursor = afterKeyword;
                    while (cursor < length && (script.charAt(cursor) == ' ' || script.charAt(cursor) == '\t')) {
                        cursor++;
                    }
                    int tokenStart = cursor;
                    while (cursor < length && script.charAt(cursor) != '\n' && script.charAt(cursor) != '\r') {
                        cursor++;
                    }
                    String token = script.substring(tokenStart, cursor).strip();
                    if (token.isEmpty()) {
                        throw new IllegalStateException("DELIMITER 指令后缺少分隔符");
                    }
                    delimiter = token;
                    i = cursor;
                    atLineStart = true;
                    continue;
                }
            }

            // 行注释：-- 后必须跟空白或行尾（MySQL 规则），# 无此要求
            // 行注释：-- 后必须跟空白或行尾（MySQL 规则），# 无此要求。
            // lineEnd() 把结尾的换行也吞进去了，所以吞完之后光标就在下一行行首，
            // atLineStart 必须置 true —— 否则「注释行紧跟 DELIMITER 行」时
            // DELIMITER 不被识别为客户端指令，会被当成 SQL 发给服务端并报语法错。
            if (c == '-' && i + 1 < length && script.charAt(i + 1) == '-'
                && (i + 2 >= length || isSpaceOrEol(script.charAt(i + 2)))) {
                int end = lineEnd(script, i);
                current.append(script, i, end);
                i = end;
                atLineStart = true;
                continue;
            }
            if (c == '#') {
                int end = lineEnd(script, i);
                current.append(script, i, end);
                i = end;
                atLineStart = true;
                continue;
            }
            // 块注释
            if (c == '/' && i + 1 < length && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                end = end < 0 ? length : end + 2;
                current.append(script, i, end);
                i = end;
                atLineStart = false;
                continue;
            }
            // 引号：整段原样吞掉，内部的 delimiter 不生效
            if (c == '\'' || c == '"' || c == '`') {
                int end = consumeQuoted(script, i, c);
                current.append(script, i, end);
                i = end;
                atLineStart = false;
                continue;
            }
            // 语句边界
            if (script.startsWith(delimiter, i)) {
                addIfNotBlank(statements, current);
                i += delimiter.length();
                atLineStart = false;
                continue;
            }

            current.append(c);
            atLineStart = c == '\n' || c == '\r' || (atLineStart && (c == ' ' || c == '\t'));
            i++;
        }

        addIfNotBlank(statements, current);
        return statements;
    }

    private static boolean isSpaceOrEol(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static int lineEnd(String script, int from) {
        int i = from;
        while (i < script.length() && script.charAt(i) != '\n') {
            i++;
        }
        return Math.min(i + 1, script.length());
    }

    /** 返回引号字面量结束后的下标（含收尾引号）。 */
    private static int consumeQuoted(String script, int from, char quote) {
        int i = from + 1;
        int length = script.length();
        while (i < length) {
            char c = script.charAt(i);
            if (c == '\\' && quote != '`') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < length && script.charAt(i + 1) == quote) {
                    i += 2; // '' 或 "" 或 `` 双写转义
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return length;
    }

    private static void addIfNotBlank(List<String> statements, StringBuilder current) {
        String sql = current.toString().strip();
        current.setLength(0);
        if (sql.isEmpty()) {
            return;
        }
        // 只剩注释的片段不必发给服务端
        if (isCommentOnly(sql)) {
            return;
        }
        statements.add(sql);
    }

    private static boolean isCommentOnly(String sql) {
        String rest = sql;
        while (!rest.isBlank()) {
            rest = rest.stripLeading();
            if (rest.startsWith("--") || rest.startsWith("#")) {
                int nl = rest.indexOf('\n');
                if (nl < 0) {
                    return true;
                }
                rest = rest.substring(nl + 1);
                continue;
            }
            if (rest.startsWith("/*")) {
                int end = rest.indexOf("*/");
                if (end < 0) {
                    return true;
                }
                rest = rest.substring(end + 2);
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean matchesIgnoreCase(String source, int offset, String keyword) {
        return source.regionMatches(true, offset, keyword, 0, keyword.length());
    }
}
