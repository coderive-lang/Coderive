package cod.lexer;

import cod.lexer.TokenType.Symbol;
import cod.lexer.TokenType.Keyword;
import java.util.List;
import java.util.ArrayList;

public class Token {
    // Non-final to allow pooling reuse - direct access, no getters/setters
    public TokenType type;
    public char[] source;
    public int start;
    public int length;
    public int line;
    public int column;
    public Symbol symbol;
    public Keyword keyword;
    public List<Token> childTokens;
    public String fileName;

    // Cached text (lazy)
    private transient String cachedText = null;
    private transient int cachedHash = 0;
    
    // Pooling support
    private static final TokenPool POOL = new TokenPool(1024);
    private boolean pooled = false;
    
    // Singleton for empty whitespace
    public static final Token EMPTY_WHITESPACE = new Token(
        TokenType.WS, new char[0], 0, 0, 0, 0, null, null, null, null, false);
    
    // ThreadLocal StringBuilder for Java 7
    private static final ThreadLocal<StringBuilder> TEXT_BUILDER = new ThreadLocal<StringBuilder>() {
        @Override
        protected StringBuilder initialValue() {
            return new StringBuilder(64);
        }
    };

    // === CONSTRUCTORS ===
    
    private Token(
        TokenType type,
        char[] source,
        int start,
        int length,
        int line,
        int column,
        Symbol symbol,
        Keyword keyword,
        List<Token> childTokens,
        String fileName,
        boolean pooled) {
        this.type = type;
        this.source = source;
        this.start = start;
        this.length = length;
        this.line = line;
        this.column = column;
        this.symbol = symbol;
        this.keyword = keyword;
        this.childTokens = childTokens != null ? childTokens : new ArrayList<Token>();
        this.fileName = fileName;
        this.pooled = pooled;
    }
    
    // Public constructor (non-pooled)
    public Token(
        TokenType type,
        char[] source,
        int start,
        int length,
        int line,
        int column,
        Symbol symbol,
        Keyword keyword,
        List<Token> childTokens,
        String fileName) {
        this(type, source, start, length, line, column, symbol, keyword, childTokens, fileName, false);
    }
    
    // Legacy constructor
    public Token(
        TokenType type,
        String text,
        int line,
        int column,
        Symbol symbol,
        Keyword keyword,
        List<Token> childTokens,
        String fileName) {
        this(
            type,
            text != null ? text.toCharArray() : new char[0],
            0,
            text != null ? text.length() : 0,
            line,
            column,
            symbol,
            keyword,
            childTokens,
            fileName);
    }

    // === FACTORY METHODS WITH POOLING ===
    
    public static Token createPooled(
        TokenType type,
        char[] source,
        int start,
        int length,
        int line,
        int column,
        Symbol symbol,
        Keyword keyword,
        List<Token> childTokens,
        String fileName) {
        
        // Don't pool whitespace or comments
        if (type == TokenType.WS || type == TokenType.LINE_COMMENT || type == TokenType.BLOCK_COMMENT) {
            return new Token(type, source, start, length, line, column, symbol, keyword, childTokens, fileName);
        }
        
        Token token = POOL.acquire();
        if (token != null) {
            token.reinit(type, source, start, length, line, column, symbol, keyword, childTokens, fileName);
            return token;
        }
        return new Token(type, source, start, length, line, column, symbol, keyword, childTokens, fileName);
    }
    
    public void reinit(
        TokenType type,
        char[] source,
        int start,
        int length,
        int line,
        int column,
        Symbol symbol,
        Keyword keyword,
        List<Token> childTokens,
        String fileName) {
        this.type = type;
        this.source = source;
        this.start = start;
        this.length = length;
        this.line = line;
        this.column = column;
        this.symbol = symbol;
        this.keyword = keyword;
        this.childTokens.clear();
        if (childTokens != null) this.childTokens.addAll(childTokens);
        this.fileName = fileName;
        this.cachedText = null;
        this.cachedHash = 0;
        this.pooled = true;
    }
    
    public void release() {
        if (pooled) {
            POOL.release(this);
        }
    }
    
    public static Token createWhitespace(char[] source, int start, int length, int line, int column) {
        if (length == 0) return EMPTY_WHITESPACE;
        return new Token(TokenType.WS, source, start, length, line, column, null, null, null, null);
    }
    
    public static Token createKeyword(char[] source, int start, int length, int line, int column, Keyword keyword) {
        return createPooled(TokenType.KEYWORD, source, start, length, line, column, null, keyword, null, null);
    }
    
    public static Token createSymbol(char[] source, int start, int length, int line, int column, Symbol symbol) {
        return createPooled(TokenType.SYMBOL, source, start, length, line, column, symbol, null, null, null);
    }
    
    public static Token createIdentifier(char[] source, int start, int length, int line, int column) {
        return createPooled(TokenType.ID, source, start, length, line, column, null, null, null, null);
    }
    
    public static Token createNumber(char[] source, int start, int length, boolean isFloat, int line, int column) {
        return createPooled(isFloat ? TokenType.FLOAT_LIT : TokenType.INT_LIT,
                source, start, length, line, column, null, null, null, null);
    }
    
    public static Token createTextLiteral(char[] source, int start, int length, int line, int column) {
        return new Token(TokenType.TEXT_LIT, source, start, length, line, column, null, null, null, null);
    }
    
    public static Token createInterpolation(int line, int column, List<Token> childTokens) {
        return new Token(TokenType.INTERPOL, new char[0], 0, 0, line, column, null, null, childTokens, null);
    }
    
    public static Token createEOF(int line, int column) {
        return new Token(TokenType.EOF, new char[0], 0, 0, line, column, null, null, null, null);
    }
    
    // Legacy factories
    public static Token createKeyword(String text, int line, int column, Keyword keyword) {
        char[] chars = text.toCharArray();
        return createKeyword(chars, 0, chars.length, line, column, keyword);
    }
    
    public static Token createSymbol(String text, int line, int column, Symbol symbol) {
        char[] chars = text.toCharArray();
        return createSymbol(chars, 0, chars.length, line, column, symbol);
    }
    
    public static Token createIdentifier(String text, int line, int column) {
        char[] chars = text.toCharArray();
        return createIdentifier(chars, 0, chars.length, line, column);
    }
    
    public static Token createNumber(String text, boolean isFloat, int line, int column) {
        char[] chars = text.toCharArray();
        return createNumber(chars, 0, chars.length, isFloat, line, column);
    }
    
    public static Token createTextLiteral(String text, int line, int column) {
        char[] chars = text.toCharArray();
        return createTextLiteral(chars, 0, chars.length, line, column);
    }

    // === FAST ACCESS METHODS ===
    
    public String getText() {
        if (cachedText == null && length > 0) {
            if (type == TokenType.TEXT_LIT && length >= 2) {
                int end = start + length - 1;
                if (length >= 4 && source[start] == '|' && source[start + 1] == '"' &&
                    source[end - 1] == '"' && source[end] == '|') {
                    cachedText = new String(source, start + 2, length - 4);
                } else if (source[start] == '"' && source[end] == '"') {
                    cachedText = new String(source, start + 1, length - 2);
                } else {
                    cachedText = new String(source, start, length);
                }
            } else {
                cachedText = new String(source, start, length);
            }
        }
        return cachedText != null ? cachedText : "";
    }
    
    public String getTextReusingBuilder() {
        if (cachedText != null) return cachedText;
        if (length == 0) return "";
        StringBuilder sb = TEXT_BUILDER.get();
        sb.setLength(0);
        if (type == TokenType.TEXT_LIT && length >= 2) {
            int end = start + length - 1;
            if (length >= 4 && source[start] == '|' && source[start + 1] == '"' &&
                source[end - 1] == '"' && source[end] == '|') {
                sb.append(source, start + 2, length - 4);
            } else if (source[start] == '"' && source[end] == '"') {
                sb.append(source, start + 1, length - 2);
            } else {
                sb.append(source, start, length);
            }
        } else {
            sb.append(source, start, length);
        }
        cachedText = sb.toString();
        return cachedText;
    }
    
    public boolean matches(String s) {
        if (s == null) return false;
        if (s.length() != length) return false;
        for (int i = 0; i < length; i++) {
            if (source[start + i] != s.charAt(i)) return false;
        }
        return true;
    }
    
    public boolean matches(char c) {
        return length == 1 && source[start] == c;
    }
    
    public boolean matchesIgnoreCase(String s) {
        if (s == null) return false;
        if (s.length() != length) return false;
        for (int i = 0; i < length; i++) {
            char c1 = source[start + i];
            char c2 = s.charAt(i);
            if (Character.toLowerCase(c1) != Character.toLowerCase(c2)) return false;
        }
        return true;
    }
    
    public char charAt(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Length: " + length);
        }
        return source[start + index];
    }
    
    public char firstChar() { return length > 0 ? source[start] : '\0'; }
    public char lastChar() { return length > 0 ? source[start + length - 1] : '\0'; }
    
    public boolean startsWith(String prefix) {
        if (prefix == null) return false;
        if (prefix.length() > length) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (source[start + i] != prefix.charAt(i)) return false;
        }
        return true;
    }
    
    public boolean startsWith(char c) { return length > 0 && source[start] == c; }
    
    public boolean endsWith(String suffix) {
        if (suffix == null) return false;
        if (suffix.length() > length) return false;
        int offset = length - suffix.length();
        for (int i = 0; i < suffix.length(); i++) {
            if (source[start + offset + i] != suffix.charAt(i)) return false;
        }
        return true;
    }
    
    public boolean endsWith(char c) { return length > 0 && source[start + length - 1] == c; }
    
    public String substring(int startOffset, int endOffset) {
        if (startOffset < 0 || endOffset > length || startOffset >= endOffset) {
            throw new IndexOutOfBoundsException();
        }
        return new String(source, this.start + startOffset, endOffset - startOffset);
    }
    
    public void substringInto(int startOffset, int endOffset, StringBuilder out) {
        if (startOffset < 0 || endOffset > length || startOffset >= endOffset) {
            throw new IndexOutOfBoundsException();
        }
        out.append(source, this.start + startOffset, endOffset - startOffset);
    }
    
    public boolean isKeyword() { return type == TokenType.KEYWORD && keyword != null; }
    public boolean isKeyword(Keyword expected) { return type == TokenType.KEYWORD && keyword == expected; }
    public boolean isSymbol() { return type == TokenType.SYMBOL && symbol != null; }
    public boolean isSymbol(Symbol expected) { return type == TokenType.SYMBOL && symbol == expected; }
    
    public boolean isSignificant() {
        return type != TokenType.WS && 
               type != TokenType.LINE_COMMENT && 
               type != TokenType.BLOCK_COMMENT;
    }
    
    public boolean isLiteral() {
        return type == TokenType.INT_LIT || type == TokenType.FLOAT_LIT ||
               type == TokenType.TEXT_LIT || type == TokenType.BOOL_LIT;
    }
    
    public boolean hasChildTokens() { return childTokens != null && !childTokens.isEmpty(); }
    public int getChildCount() { return childTokens == null ? 0 : childTokens.size(); }
    public Token getChild(int index) {
        return (childTokens != null && index >= 0 && index < childTokens.size()) 
            ? childTokens.get(index) : null;
    }
    
    public String getTextLegacy() { return getText(); }
    
    public void releaseSource() {
        if (cachedText == null && length > 0) {
            cachedText = getText();
        }
    }
    
    public int compareTo(Token other) {
        if (other == null) return 1;
        int minLen = Math.min(length, other.length);
        for (int i = 0; i < minLen; i++) {
            char c1 = source[start + i];
            char c2 = other.source[other.start + i];
            if (c1 != c2) return c1 - c2;
        }
        return length - other.length;
    }
    
    @Override
    public int hashCode() {
        if (cachedHash != 0) return cachedHash;
        int result = type.hashCode();
        for (int i = 0; i < length && i < 32; i++) {
            result = 31 * result + source[start + i];
        }
        cachedHash = result;
        return result;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Token)) return false;
        Token other = (Token) obj;
        if (type != other.type) return false;
        if (length != other.length) return false;
        for (int i = 0; i < length; i++) {
            if (source[start + i] != other.source[other.start + i]) return false;
        }
        return true;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("Token{type=").append(type.name());
        sb.append(", text='").append(getText()).append('\'');
        if (symbol != null) sb.append(", symbol=").append(symbol.name());
        if (keyword != null) sb.append(", keyword=").append(keyword.name());
        if (childTokens != null && !childTokens.isEmpty())
            sb.append(", childTokens=").append(childTokens.size());
        if (fileName != null) sb.append(", file='").append(fileName).append('\'');
        sb.append(", line=").append(line);
        sb.append(", column=").append(column);
        sb.append('}');
        return sb.toString();
    }
}

// TokenPool class
class TokenPool {
    private final Token[] pool;
    private int index = 0;
    
    public TokenPool(int maxSize) {
        this.pool = new Token[maxSize];
    }
    
    public synchronized Token acquire() {
        if (index > 0) {
            Token token = pool[--index];
            pool[index] = null;
            return token;
        }
        return null;
    }
    
    public synchronized void release(Token token) {
        if (index < pool.length && token != null && token != Token.EMPTY_WHITESPACE) {
            pool[index++] = token;
        }
    }
}