package com.example.esrag.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具3：计算器（自研递归下降解析器，不依赖任何第三方表达式库）
 * 支持 + - * / % ^ 括号、小数、一元正负号，右结合幂运算。
 * 面试可讲：为什么不用脚本引擎（Java 17 已移除 Nashorn）而自研解析器。
 */
@Slf4j
@Component
public class CalculatorTool implements AgentTool {


    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "计算数学表达式，支持 + - * / % ^ 和括号，例如 (3+5)*2^3。当问题涉及数值计算时调用。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "expression", Map.of("type", "string", "description", "要计算的数学表达式")
                ),
                "required", List.of("expression")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object raw = arguments.get("expression");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return "{\"error\": \"expression 参数不能为空\"}";
        }
        String expr = String.valueOf(raw).trim();
        try {
            double result = new Parser(expr).parse();
            return "{\"expression\": \"" + expr.replace("\"", "'") + "\", \"result\": " + format(result) + "}";
        } catch (Exception e) {
            log.warn("计算失败: {} | {}", expr, e.getMessage());
            return "{\"expression\": \"" + expr.replace("\"", "'") + "\", \"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private String format(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /**
     * 递归下降解析器：Expr -> Term -> Factor -> Power -> Primary
     */
    static final class Parser {
        private final String expr;
        private int pos;

        Parser(String expr) {
            this.expr = expr;
        }

        double parse() {
            double value = parseExpr();
            skipSpaces();
            if (pos < expr.length()) {
                throw new IllegalArgumentException("无法解析的字符: " + expr.charAt(pos));
            }
            return value;
        }

        private double parseExpr() {
            double value = parseTerm();
            while (true) {
                skipSpaces();
                if (match('+')) {
                    value += parseTerm();
                } else if (match('-')) {
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parseFactor();
            while (true) {
                skipSpaces();
                if (match('*')) {
                    value *= parseFactor();
                } else if (match('/')) {
                    double divisor = parseFactor();
                    if (divisor == 0) {
                        throw new ArithmeticException("除数不能为 0");
                    }
                    value /= divisor;
                } else if (match('%')) {
                    double divisor = parseFactor();
                    if (divisor == 0) {
                        throw new ArithmeticException("取模除数不能为 0");
                    }
                    value %= divisor;
                } else {
                    return value;
                }
            }
        }

        private double parseFactor() {
            skipSpaces();
            if (match('-')) {
                return -parseFactor();
            }
            if (match('+')) {
                return parseFactor();
            }
            return parsePower();
        }

        private double parsePower() {
            double base = parsePrimary();
            skipSpaces();
            if (match('^')) {
                // 右结合：2^3^2 = 2^(3^2)
                return Math.pow(base, parseFactor());
            }
            return base;
        }

        private double parsePrimary() {
            skipSpaces();
            if (match('(')) {
                double value = parseExpr();
                skipSpaces();
                if (!match(')')) {
                    throw new IllegalArgumentException("缺少右括号");
                }
                return value;
            }
            int start = pos;
            while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("无法解析的字符: " + (pos < expr.length() ? expr.charAt(pos) : "表达式结尾"));
            }
            try {
                return Double.parseDouble(expr.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("数字格式错误: " + expr.substring(start, pos));
            }
        }

        private boolean match(char c) {
            if (pos < expr.length() && expr.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (pos < expr.length() && Character.isWhitespace(expr.charAt(pos))) {
                pos++;
            }
        }
    }
}
