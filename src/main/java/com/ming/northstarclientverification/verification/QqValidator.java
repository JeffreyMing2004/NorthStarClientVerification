package com.ming.northstarclientverification.verification;

import com.ming.northstarclientverification.Config;

import java.util.regex.Pattern;

/**
 * QQ 号格式校验与规范化。
 */
public final class QqValidator {

    private QqValidator() {
    }

    /**
     * 去掉空白并统一全角数字，返回纯数字字符串。
     * 例如 "１２３４５ " -> "12345"。
     */
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= '\uFF10' && c <= '\uFF19') {
                // 全角数字 ０-９ -> 半角 0-9
                sb.append((char) (c - '\uFF10' + '0'));
            } else if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 是否为合法的 QQ 号。 */
    public static boolean isValid(String qq) {
        if (qq == null || qq.isEmpty()) {
            return false;
        }
        Pattern pattern = Config.qqRegex;
        if (pattern == null) {
            pattern = Pattern.compile(Config.DEFAULT_QQ_PATTERN);
        }
        return pattern.matcher(qq).matches();
    }
}
