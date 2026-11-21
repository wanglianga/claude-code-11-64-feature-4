package com.ccdev.drainage.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 判读规程条件求值器（纯函数，便于单测）：
 *   条件表达式为若干谓词以 " AND " 连接，每个谓词形如 `field >= 3`、`seepage = TRUE`。
 *   求值结果三态：TRUE / FALSE / UNKNOWN（字段缺失或不可比较）。
 *   引擎本身不访问数据库，“缺失字段按何种证据缺口处理”由调用方依据字段字典决定。
 */
public final class ProtocolRuleEngine {

    private ProtocolRuleEngine() {
    }

    public record Condition(String field, String op, String value) {
    }

    public enum Truth {TRUE, FALSE, UNKNOWN}

    public record PredicateResult(Truth truth, String field, String detail) {
    }

    public record ExpressionResult(Truth truth, List<String> missingFields, List<PredicateResult> predicates) {
    }

    /** 解析条件表达式；空表达式表示无条件成立。 */
    public static List<Condition> parse(String expr) {
        List<Condition> result = new ArrayList<>();
        if (expr == null || expr.isBlank()) {
            return result;
        }
        for (String rawPart : expr.split("(?i)\\s+AND\\s+")) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }
            String op = detectOperator(part);
            if (op == null) {
                // 单独字段名视为“该字段存在即为真”
                result.add(new Condition(part, "EXISTS", "TRUE"));
                continue;
            }
            int idx = part.indexOf(op);
            String field = part.substring(0, idx).trim();
            String value = part.substring(idx + op.length()).trim();
            result.add(new Condition(field, op, stripQuotes(value)));
        }
        return result;
    }

    private static String detectOperator(String part) {
        for (String op : new String[]{">=", "<=", "!=", "=", ">", "<"}) {
            if (part.contains(op)) {
                return op;
            }
        }
        return null;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && (value.startsWith("'") && value.endsWith("'")
                || value.startsWith("\"") && value.endsWith("\""))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 求值单个谓词。
     *
     * @param actualValue 字段实际值；null 表示缺失
     */
    public static Truth evaluatePredicate(Condition c, String actualValue) {
        if (actualValue == null) {
            return Truth.UNKNOWN;
        }
        if ("EXISTS".equals(c.op())) {
            return actualValue.isBlank() ? Truth.FALSE : Truth.TRUE;
        }
        BigDecimal a = tryDecimal(actualValue);
        BigDecimal b = tryDecimal(c.value());
        if (a != null && b != null) {
            int cmp = a.compareTo(b);
            return switch (c.op()) {
                case ">=" -> cmp >= 0 ? Truth.TRUE : Truth.FALSE;
                case "<=" -> cmp <= 0 ? Truth.TRUE : Truth.FALSE;
                case ">" -> cmp > 0 ? Truth.TRUE : Truth.FALSE;
                case "<" -> cmp < 0 ? Truth.TRUE : Truth.FALSE;
                case "=" -> cmp == 0 ? Truth.TRUE : Truth.FALSE;
                case "!=" -> cmp != 0 ? Truth.TRUE : Truth.FALSE;
                default -> Truth.UNKNOWN;
            };
        }
        String left = actualValue.trim().toUpperCase(Locale.ROOT);
        String right = c.value().trim().toUpperCase(Locale.ROOT);
        return switch (c.op()) {
            case "=" -> left.equals(right) ? Truth.TRUE : Truth.FALSE;
            case "!=" -> !left.equals(right) ? Truth.TRUE : Truth.FALSE;
            default -> Truth.UNKNOWN;
        };
    }

    private static BigDecimal tryDecimal(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 求值整条 AND 表达式。
     *
     * @param valueLookup 字段取值器：返回 null 表示缺失
     */
    public static ExpressionResult evaluate(List<Condition> conditions, FieldLookup valueLookup) {
        List<PredicateResult> details = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        boolean anyUnknown = false;
        for (Condition c : conditions) {
            String actual = valueLookup.lookup(c.field());
            Truth t = evaluatePredicate(c, actual);
            details.add(new PredicateResult(t, c.field(), actual == null ? "字段缺失" : actual));
            if (t == Truth.UNKNOWN) {
                anyUnknown = true;
                if (!missing.contains(c.field())) {
                    missing.add(c.field());
                }
            } else if (t == Truth.FALSE) {
                return new ExpressionResult(Truth.FALSE, List.of(), details);
            }
        }
        return new ExpressionResult(anyUnknown ? Truth.UNKNOWN : Truth.TRUE, missing, details);
    }

    @FunctionalInterface
    public interface FieldLookup {
        String lookup(String field);
    }
}
