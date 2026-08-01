package com.xiyiyun.shop.it.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 批次7 发现的切分器缺陷回归：注释行紧跟 DELIMITER 行时，DELIMITER 没被识别。
 *
 * <p>{@code lineEnd()} 会把行注释末尾的换行一起吞掉，光标停在下一行行首，
 * 但原实现之后把 {@code atLineStart} 置成 false，于是下一行的 {@code DELIMITER}
 * 被当成普通 SQL 文本累积，最终整段自定义分隔符的过程体被作为一条语句发给服务端，
 * 报 “syntax error near 'DELIMITER $'”。002-006 只是碰巧在 DELIMITER 前留了空行才没踩到。
 */
class SqlScriptRunnerSplitTest {

    @Test
    @DisplayName("行注释紧跟 DELIMITER 时仍按客户端指令处理")
    void delimiterAfterLineCommentIsRecognized() {
        String script = """
            CREATE TABLE t (id INT);
            -- 幂等 DDL 辅助过程
            DELIMITER $
            DROP PROCEDURE IF EXISTS p $
            CREATE PROCEDURE p()
            BEGIN
              SELECT 1;
              SELECT 2;
            END $
            DELIMITER ;
            INSERT INTO t VALUES (1);
            """;

        List<String> statements = SqlScriptRunner.split(script);

        // 过程体必须是「一条」语句，内部的 ; 不得成为边界
        assertThat(statements).anySatisfy(statement ->
            assertThat(statement)
                .contains("CREATE PROCEDURE p()")
                .contains("SELECT 1;")
                .contains("SELECT 2;")
                .contains("END"));
        // DELIMITER 本身不能作为语句发给服务端
        assertThat(statements).noneMatch(statement -> statement.strip().toUpperCase().startsWith("DELIMITER"));
        assertThat(statements).anySatisfy(statement -> assertThat(statement).contains("INSERT INTO t VALUES (1)"));
    }

    @Test
    @DisplayName("# 注释紧跟 DELIMITER 时同样识别")
    void delimiterAfterHashCommentIsRecognized() {
        String script = """
            # 注释
            DELIMITER $$
            SELECT 1; SELECT 2 $$
            DELIMITER ;
            """;

        List<String> statements = SqlScriptRunner.split(script);

        assertThat(statements).noneMatch(statement -> statement.strip().toUpperCase().startsWith("DELIMITER"));
        assertThat(statements).anySatisfy(statement ->
            assertThat(statement).contains("SELECT 1;").contains("SELECT 2"));
    }

    @Test
    @DisplayName("引号内的分隔符不切句")
    void delimiterInsideQuotesIsLiteral() {
        List<String> statements = SqlScriptRunner.split("INSERT INTO t VALUES ('a;b'); SELECT 1;");

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("'a;b'");
    }
}
