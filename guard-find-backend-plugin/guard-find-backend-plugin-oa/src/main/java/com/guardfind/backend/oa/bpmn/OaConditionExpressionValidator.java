/*
 * Copyright (c) 2022-present Charles7c Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.guardfind.backend.oa.bpmn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 受限条件表达式校验：只允许变量、字面量、比较、括号与 &&/||/!。
 *
 * <p>
 * 不接受方法调用、构造函数、静态类型引用、Bean 引用、算术与文本 EL 关键字，
 * 避免把设计者输入直接交给任意 JUEL 执行。
 * </p>
 */
public final class OaConditionExpressionValidator {

    private static final int MAX_LENGTH = 2000;

    private static final Set<String> RESERVED = Set
        .of("and", "or", "not", "eq", "ne", "lt", "le", "gt", "ge", "div", "mod", "empty", "null", "instanceof", "new");

    private OaConditionExpressionValidator() {
    }

    public static boolean isSafe(String expression) {
        try {
            requireSafe(expression);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static void requireSafe(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("条件表达式不能为空");
        }
        String body = stripWrapper(expression.trim());
        if (body.isBlank()) {
            throw new IllegalArgumentException("条件表达式不能为空");
        }
        if (body.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("条件表达式过长");
        }
        if (body.indexOf('{') >= 0 || body.indexOf('}') >= 0) {
            throw new IllegalArgumentException("条件表达式不允许嵌套花括号");
        }
        new Parser(tokenize(body)).parse();
    }

    private static String stripWrapper(String expression) {
        if (!expression.startsWith("${") || !expression.endsWith("}")) {
            throw new IllegalArgumentException("条件表达式必须使用 ${...} 包裹");
        }
        return expression.substring(2, expression.length() - 1).trim();
    }

    private static List<Token> tokenize(String body) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < body.length()) {
            char current = body.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '\'' || current == '"') {
                index = readString(body, index, tokens);
                continue;
            }
            if (current == '(' || current == ')') {
                tokens.add(new Token(TokenType.SYMBOL, Character.toString(current)));
                index++;
                continue;
            }
            if (current == '!' || current == '=' || current == '<' || current == '>') {
                String symbol = readSymbol(body, index);
                tokens.add(new Token(TokenType.SYMBOL, symbol));
                index += symbol.length();
                continue;
            }
            if (current == '&' || current == '|') {
                if (index + 1 >= body.length() || body.charAt(index + 1) != current) {
                    throw new IllegalArgumentException("条件表达式包含非法运算符");
                }
                tokens.add(new Token(TokenType.SYMBOL, current + "" + current));
                index += 2;
                continue;
            }
            if (Character.isDigit(current) || (current == '-' && index + 1 < body.length() && Character.isDigit(body
                .charAt(index + 1)))) {
                index = readNumber(body, index, tokens);
                continue;
            }
            if (Character.isLetter(current) || current == '_') {
                index = readIdentifier(body, index, tokens);
                continue;
            }
            throw new IllegalArgumentException("条件表达式包含非法字符: " + current);
        }
        tokens.add(new Token(TokenType.EOF, ""));
        return tokens;
    }

    private static int readString(String body, int start, List<Token> tokens) {
        char quote = body.charAt(start);
        StringBuilder value = new StringBuilder();
        int index = start + 1;
        while (index < body.length()) {
            char current = body.charAt(index);
            if (current == '\\') {
                if (index + 1 >= body.length()) {
                    throw new IllegalArgumentException("条件表达式字符串未闭合");
                }
                value.append(current).append(body.charAt(index + 1));
                index += 2;
                continue;
            }
            if (current == quote) {
                tokens.add(new Token(TokenType.STRING, value.toString()));
                return index + 1;
            }
            value.append(current);
            index++;
        }
        throw new IllegalArgumentException("条件表达式字符串未闭合");
    }

    private static String readSymbol(String body, int index) {
        char current = body.charAt(index);
        if (current == '!') {
            if (index + 1 < body.length() && body.charAt(index + 1) == '=') {
                return "!=";
            }
            return "!";
        }
        if (index + 1 < body.length() && body.charAt(index + 1) == '=') {
            return current + "=";
        }
        if (current == '=') {
            throw new IllegalArgumentException("条件表达式不允许单等号");
        }
        return Character.toString(current);
    }

    private static int readNumber(String body, int start, List<Token> tokens) {
        int index = start;
        if (body.charAt(index) == '-') {
            index++;
        }
        boolean hasDot = false;
        while (index < body.length()) {
            char current = body.charAt(index);
            if (Character.isDigit(current)) {
                index++;
                continue;
            }
            if (current == '.' && !hasDot) {
                hasDot = true;
                index++;
                continue;
            }
            break;
        }
        String number = body.substring(start, index);
        if (number.endsWith(".")) {
            throw new IllegalArgumentException("条件表达式数字格式非法");
        }
        tokens.add(new Token(TokenType.NUMBER, number));
        return index;
    }

    private static int readIdentifier(String body, int start, List<Token> tokens) {
        int index = start;
        while (index < body.length()) {
            char current = body.charAt(index);
            if (Character.isLetterOrDigit(current) || current == '_') {
                index++;
                continue;
            }
            if (current == '.' && index + 1 < body.length() && (Character.isLetter(body.charAt(index + 1)) || body
                .charAt(index + 1) == '_')) {
                index++;
                continue;
            }
            break;
        }
        String identifier = body.substring(start, index);
        String lower = identifier.toLowerCase(Locale.ROOT);
        if (RESERVED.contains(lower)) {
            throw new IllegalArgumentException("条件表达式不允许关键字: " + identifier);
        }
        TokenType type = switch (lower) {
            case "true", "false" -> TokenType.BOOLEAN;
            default -> TokenType.IDENTIFIER;
        };
        tokens.add(new Token(type, identifier));
        return index;
    }

    private enum TokenType {
        IDENTIFIER, NUMBER, STRING, BOOLEAN, SYMBOL, EOF
    }

    private record Token(TokenType type, String text) {
    }

    private static final class Parser {

        private final List<Token> tokens;
        private int position;

        private Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        private void parse() {
            parseOr();
            if (!peek().type().equals(TokenType.EOF)) {
                throw new IllegalArgumentException("条件表达式包含无法解析的内容: " + peek().text());
            }
        }

        private void parseOr() {
            parseAnd();
            while (match("||")) {
                parseAnd();
            }
        }

        private void parseAnd() {
            parseUnary();
            while (match("&&")) {
                parseUnary();
            }
        }

        private void parseUnary() {
            if (match("!")) {
                parseUnary();
                return;
            }
            if (match("(")) {
                parseOr();
                expect(")");
                return;
            }
            parseComparison();
        }

        private void parseComparison() {
            parsePrimary();
            if (isComparison(peek().text())) {
                position++;
                parsePrimary();
            }
        }

        private void parsePrimary() {
            Token token = peek();
            if (token.type() == TokenType.NUMBER || token.type() == TokenType.STRING || token
                .type() == TokenType.BOOLEAN || token.type() == TokenType.IDENTIFIER) {
                position++;
                return;
            }
            throw new IllegalArgumentException("条件表达式缺少操作数");
        }

        private boolean isComparison(String text) {
            return "==".equals(text) || "!=".equals(text) || ">".equals(text) || ">=".equals(text) || "<"
                .equals(text) || "<=".equals(text);
        }

        private boolean match(String symbol) {
            if (peek().type() == TokenType.SYMBOL && symbol.equals(peek().text())) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(String symbol) {
            if (!match(symbol)) {
                throw new IllegalArgumentException("条件表达式缺少 " + symbol);
            }
        }

        private Token peek() {
            return tokens.get(position);
        }
    }
}
