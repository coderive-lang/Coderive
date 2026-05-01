package cod.lexer;

import java.util.*;

public class StringLexer {

    private final LexerSource source;
    private final List<String> extractedStrings;
    private boolean extractionMode = false;
    
    private static final class UnicodeEscapeResult {
        private final String text;
        private final int consumedChars;
        private UnicodeEscapeResult(String text, int consumedChars) {
            this.text = text; this.consumedChars = consumedChars;
        }
    }

    public StringLexer(LexerSource source) {
        this.source = source;
        this.extractedStrings = new ArrayList<String>();
    }
    
    private boolean isHexDigit(char c) { return c < 128 && CharClassifier.IS_HEX[c]; }
    private int hexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
    
    private int readUnicodeUnit() {
        if (source.getPosition() + 4 > source.getInputArray().length) throw new RuntimeException("Syntax Error: Incomplete Unicode escape at line " + source.getLine());
        int value = 0;
        for (int i = 0; i < 4; i++) {
            char digit = source.peek();
            if (!isHexDigit(digit)) throw new RuntimeException("Syntax Error: Invalid Unicode escape at line " + source.getLine());
            source.consume();
            value = (value << 4) + hexValue(digit);
        }
        return value;
    }
    
    private UnicodeEscapeResult decodeUnicodeEscape() {
        int consumed = 4;
        int firstUnit = readUnicodeUnit();
        if (Character.isLowSurrogate((char)firstUnit)) throw new RuntimeException("Syntax Error: Unexpected low surrogate in Unicode escape at line " + source.getLine());
        if (Character.isHighSurrogate((char)firstUnit)) {
            if (source.getPosition() + 5 >= source.getInputArray().length) throw new RuntimeException("Syntax Error: Missing low surrogate in Unicode escape at line " + source.getLine());
            if (source.peek() != '\\' || source.peek(1) != 'u') throw new RuntimeException("Syntax Error: Expected low surrogate escape at line " + source.getLine());
            source.consume(); source.consume(); consumed += 2;
            int secondUnit = readUnicodeUnit(); consumed += 4;
            if (!Character.isLowSurrogate((char)secondUnit)) throw new RuntimeException("Syntax Error: Invalid low surrogate in Unicode escape at line " + source.getLine());
            return new UnicodeEscapeResult(new String(new char[] {(char)firstUnit, (char)secondUnit}), consumed);
        }
        return new UnicodeEscapeResult(String.valueOf((char)firstUnit), consumed);
    }

    public Token scan() {
        if (source.peek() == '|' && source.peek(1) == '"') return readMultilineText();
        if (source.peek() == '"') return readText();
        return null;
    }

    private Token readText() {
        int startLine = source.getLine();
        int startCol = source.getColumn();
        int startPos = source.getPosition();
        int length = 0;
        List<Token> parts = new ArrayList<Token>();
        List<Token> childTokens = new ArrayList<Token>();
        boolean isMultiline = source.peek() == '|' && source.peek(1) == '"';

        if (isMultiline) { source.consume(); source.consume(); length += 2; }
        else { source.consume(); length++; }
        
        int textStart = source.getPosition();
        int textLength = 0;
        
        while (source.getPosition() < source.getInputArray().length) {
            char c = source.peek();
            if (!isMultiline && c == '\n') throw new RuntimeException("Syntax Error: Unterminated string at line " + startLine);
            if (!isMultiline && c == '"') { source.consume(); length++; break; }
            else if (isMultiline && c == '"' && source.peek(1) == '|') { source.consume(); source.consume(); length += 2; break; }
            
            if (c == '\\') {
                if (textLength > 0) {
                    Token textToken = Token.createTextLiteral(source.getInputArray(), textStart, textLength, startLine, startCol);
                    parts.add(textToken); childTokens.add(textToken);
                    textStart = source.getPosition(); textLength = 0;
                }
                source.consume(); length++;
                if (source.getPosition() >= source.getInputArray().length) throw new RuntimeException("Syntax Error: Unterminated escape at line " + source.getLine());
                char escaped = source.consume(); length++;
                String escapedStr;
                if (escaped == 'u') {
                    UnicodeEscapeResult res = decodeUnicodeEscape();
                    escapedStr = res.text; length += res.consumedChars;
                } else {
                    char actual;
                    switch (escaped) {
                        case 'n': actual = '\n'; break;
                        case 't': actual = '\t'; break;
                        case 'r': actual = '\r'; break;
                        case '\\': actual = '\\'; break;
                        case '"': actual = '"'; break;
                        case '{': actual = '{'; break;
                        default: actual = escaped; break;
                    }
                    escapedStr = String.valueOf(actual);
                }
                Token escToken = Token.createTextLiteral(escapedStr, source.getLine(), source.getColumn() - 1);
                parts.add(escToken); childTokens.add(escToken);
                textStart = source.getPosition(); textLength = 0; continue;
            }
            
            if (c == '{' && !isMultiline) {
                if (textLength > 0) {
                    Token textToken = Token.createTextLiteral(source.getInputArray(), textStart, textLength, startLine, startCol);
                    parts.add(textToken); childTokens.add(textToken);
                }
                int braceLine = source.getLine(); int braceCol = source.getColumn();
                source.consume(); length++;
                int exprStart = source.getPosition(); int exprLen = 0; int depth = 1;
                while (source.getPosition() < source.getInputArray().length && depth > 0) {
                    char ch = source.peek();
                    if (ch == '{') depth++; else if (ch == '}') depth--;
                    source.consume(); exprLen++;
                }
                char[] exprSlice = Arrays.copyOfRange(source.getInputArray(), exprStart, exprStart + exprLen - 1);
                MainLexer exprLexer = new MainLexer(new String(exprSlice), true);
                List<Token> exprTokens = exprLexer.tokenize();
                Token interpToken = Token.createInterpolation(braceLine, braceCol, exprTokens);
                parts.add(interpToken); childTokens.addAll(exprTokens);
                textStart = source.getPosition(); textLength = 0; continue;
            }
            source.consume(); textLength++; length++;
        }
        
        if (textLength > 0) {
            Token textToken = Token.createTextLiteral(source.getInputArray(), textStart, textLength, startLine, startCol);
            parts.add(textToken); childTokens.add(textToken);
        }
        
        StringBuilder fullText = new StringBuilder(isMultiline ? "|\"" : "\"");
        for (Token p : parts) fullText.append(p.getText());
        fullText.append(isMultiline ? "\"|" : "\"");
        if (extractionMode) extractedStrings.add(fullText.toString());
        
        boolean hasInterp = false;
        for (Token p : parts) if (p.type == TokenType.INTERPOL) { hasInterp = true; break; }
        if (!hasInterp) return Token.createTextLiteral(fullText.toString(), startLine, startCol);
        return new Token(TokenType.INTERPOL, source.getInputArray(), startPos, length, startLine, startCol, null, null, parts, null);
    }

    private Token readMultilineText() {
        int startLine = source.getLine();
        int startCol = source.getColumn();
        int startPos = source.getPosition();
        if (!(source.peek() == '|' && source.peek(1) == '"')) throw new RuntimeException("Invalid multiline delimiter");
        int baseline = startCol; int length = 0;
        source.consume(); source.consume(); length += 2;
        
        while (source.getPosition() < source.getInputArray().length) {
            char after = source.peek();
            if (after == '\n') break;
            else if (after >= 128 || !CharClassifier.IS_WHITESPACE[after]) throw new RuntimeException("Syntax Error: Illegal multiline content start at " + startLine);
            source.consume(); length++;
        }
        if (source.getPosition() < source.getInputArray().length && source.peek() == '\n') {
            source.consume(); length++; source.setLine(source.getLine() + 1); source.setColumn(1);
        }
        
        List<Token> interps = new ArrayList<Token>();
        List<Token> children = new ArrayList<Token>();
        int currentCol = 1; StringBuilder currentLine = new StringBuilder();
        
        while (source.getPosition() < source.getInputArray().length) {
            char c = source.peek();
            if (c == '"' && source.peek(1) == '|') {
                if (currentLine.length() > 0) {
                    int skip = 0; while (skip < currentLine.length() && skip < baseline - 1) {
                        char ch = currentLine.charAt(skip); if (ch == ' ' || ch == '\t') skip++; else break;
                    }
                    if (skip < currentLine.length()) {
                        Token txt = Token.createTextLiteral(currentLine.substring(skip), startLine, startCol);
                        interps.add(txt); children.add(txt);
                    }
                }
                source.consume(); source.consume(); length += 2;
                StringBuilder finalText = new StringBuilder("|\"");
                for (Token p : interps) finalText.append(p.getText());
                finalText.append("\"|");
                if (extractionMode) extractedStrings.add(finalText.toString());
                if (interps.size() == 1 && interps.get(0).type == TokenType.TEXT_LIT) return Token.createTextLiteral(source.getInputArray(), startPos, length, startLine, startCol);
                return new Token(TokenType.INTERPOL, source.getInputArray(), startPos, length, startLine, startCol, null, null, interps, null);
            }
            
            if (c == '\n') {
                if (currentLine.length() > 0) {
                    int skip = 0; while (skip < currentLine.length() && skip < baseline - 1) {
                        char ch = currentLine.charAt(skip); if (ch == ' ' || ch == '\t') skip++; else break;
                    }
                    if (skip < currentLine.length()) {
                        Token txt = Token.createTextLiteral(currentLine.substring(skip), startLine, startCol);
                        interps.add(txt); children.add(txt);
                    }
                }
                Token nl = Token.createTextLiteral("\n", source.getLine(), source.getColumn());
                interps.add(nl); children.add(nl);
                source.consume(); length++; source.setLine(source.getLine() + 1); source.setColumn(1); currentCol = 1; currentLine.setLength(0); continue;
            }
            
            if (currentCol < baseline && (c >= 128 || !CharClassifier.IS_WHITESPACE[c]) && c != '\\' && c != '{') throw new RuntimeException("Multiline violation at line " + source.getLine());
            
            if (c == '\\') {
                source.consume(); length++; currentCol++;
                if (source.getPosition() >= source.getInputArray().length) throw new RuntimeException("Unterminated escape");
                char esc = source.consume(); length++; currentCol++;
                if (esc == 'u') { UnicodeEscapeResult res = decodeUnicodeEscape(); currentLine.append(res.text); length += res.consumedChars; currentCol += res.consumedChars; }
                else {
                    char act; switch (esc) {
                        case 'n': act = '\n'; break;
                        case 't': act = '\t'; break;
                        case 'r': act = '\r'; break;
                        case '\\': act = '\\'; break;
                        case '"': act = '"'; break;
                        case '{': act = '{'; break;
                        default: act = esc; break;
                    }
                    currentLine.append(act);
                }
                continue;
            }
            
            if (c == '{') {
                if (currentLine.length() > 0) {
                    int skip = 0; while (skip < currentLine.length() && skip < baseline - 1) {
                        char ch = currentLine.charAt(skip); if (ch == ' ' || ch == '\t') skip++; else break;
                    }
                    if (skip < currentLine.length()) {
                        Token txt = Token.createTextLiteral(currentLine.substring(skip), startLine, startCol);
                        interps.add(txt); children.add(txt);
                    }
                    currentLine.setLength(0);
                }
                int bLine = source.getLine(); int bCol = source.getColumn(); source.consume(); length++; currentCol++;
                StringBuilder expr = new StringBuilder(); int depth = 1;
                while (source.getPosition() < source.getInputArray().length && depth > 0) {
                    char ch = source.peek();
                    if (ch == '\\') { expr.append(source.consume()); if (source.getPosition() < source.getInputArray().length) expr.append(source.consume()); }
                    else if (ch == '{') { depth++; expr.append(source.consume()); }
                    else if (ch == '}') { depth--; if (depth > 0) expr.append(source.consume()); else source.consume(); }
                    else if (ch == '\n') { expr.append('\n'); source.consume(); source.setLine(source.getLine() + 1); source.setColumn(1); currentCol = 1; }
                    else { expr.append(source.consume()); currentCol++; }
                }
                MainLexer exprLex = new MainLexer(expr.toString(), true); List<Token> tokens = exprLex.tokenize();
                Token interp = Token.createInterpolation(bLine, bCol, tokens); children.addAll(tokens); interps.add(interp);
            } else {
                source.consume(); currentLine.append(c); length++; currentCol++;
            }
        }
        throw new RuntimeException("Unterminated multiline string");
    }

    public List<String> extractAllStrings() {
        extractionMode = true; extractedStrings.clear();
        int sPos = source.getPosition(); int sLine = source.getLine(); int sCol = source.getColumn();
        source.setPosition(0); source.setLine(1); source.setColumn(1);
        while (source.getPosition() < source.getInputArray().length) {
            Token t = scan(); if (t == null) source.consume();
        }
        source.setPosition(sPos); source.setLine(sLine); source.setColumn(sCol);
        extractionMode = false; return new ArrayList<String>(extractedStrings);
    }
}
