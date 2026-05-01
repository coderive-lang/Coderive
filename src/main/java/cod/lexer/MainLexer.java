package cod.lexer;

import java.util.*;

public class MainLexer implements LexerSource {

    private final CommentLexer commentLexer;
    private final NumberLexer numberLexer;
    private final IdentifierLexer identifierLexer;
    private final StringLexer stringLexer;
    private final SymbolLexer symbolLexer;
    private final WhitespaceLexer whitespaceLexer;

    private char[] input;
    private int position = 0;
    private int line = 1;
    private int column = 1;
    private int tokenStartPos = 0;
    private int tokenStartLine = 1;
    private int tokenStartCol = 1;
    private final String originalInput;
    private final boolean isTemporary;
    
    // Flags for skipping modes
    private boolean skipWhitespace = true;
    private boolean skipComments = true;
    private boolean extractComments = false;
    private boolean extractWhitespace = false;
    
    // Statistics for optimization
    private int tokensCreated = 0;
    private int tokensSkipped = 0;
    
    // Java 7 compatible ThreadLocal for token building
    private static final ThreadLocal<StringBuilder> TOKEN_BUILDER = new ThreadLocal<StringBuilder>() {
        @Override
        protected StringBuilder initialValue() {
            return new StringBuilder(64);
        }
    };

    public MainLexer(String input) {
        this(input, false);
    }

    public MainLexer(String input, boolean isTemporary) {
        String normalized = input.replace("\r\n", "\n").replace("\r", "\n");
        this.input = normalized.toCharArray();
        this.originalInput = normalized;
        this.isTemporary = isTemporary;

        this.commentLexer = new CommentLexer(this);
        this.numberLexer = new NumberLexer(this);
        this.identifierLexer = new IdentifierLexer(this);
        this.stringLexer = new StringLexer(this);
        this.symbolLexer = new SymbolLexer(this);
        this.whitespaceLexer = new WhitespaceLexer(this);
    }

    // === Configuration Methods ===
    
    public void setSkipWhitespace(boolean skip) { this.skipWhitespace = skip; }
    public void setSkipComments(boolean skip) { this.skipComments = skip; }
    public void setExtractComments(boolean extract) { this.extractComments = extract; }
    public void setExtractWhitespace(boolean extract) { this.extractWhitespace = extract; }
    
    public int getTokensCreated() { return tokensCreated; }
    public int getTokensSkipped() { return tokensSkipped; }
    public double getSkipRate() { 
        return tokensCreated + tokensSkipped == 0 ? 0 : 
               (double)tokensSkipped / (tokensCreated + tokensSkipped) * 100;
    }
    
    public void resetStats() {
        tokensCreated = 0;
        tokensSkipped = 0;
    }

    // === Core Tokenization with Zero-Allocation Skipping ===
    
    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<Token>();
        tokensCreated = 0;
        tokensSkipped = 0;
        
        while (position < input.length) {
            Token token = scanNextToken();
            if (token != null) {
                tokens.add(token);
                tokensCreated++;
            } else {
                tokensSkipped++;
            }
        }
        
        if (!isTemporary) {
            tokens.add(createEOFToken());
            tokensCreated++;
        }
        
        return tokens;
    }
    
    /**
     * Fast token scanning with zero-allocation skipping.
     * Returns null for skipped tokens (whitespace/comments).
     */
    private Token scanNextToken() {
        // Fast inline whitespace skipping - no method calls, no object creation
        while (position < input.length) {
            char c = input[position];
            
            if (c < 128 && CharClassifier.IS_WHITESPACE[c]) {
                if (skipWhitespace) {
                    // Skip without creating token
                    if (c == '\n') { line++; column = 1; }
                    else { column++; }
                    position++;
                    continue;
                } else if (extractWhitespace) {
                    // Record but don't return token
                    whitespaceLexer.recordWhitespacePosition(position, line, column);
                    if (c == '\n') { line++; column = 1; }
                    else { column++; }
                    position++;
                    continue;
                } else {
                    // Create minimal whitespace token
                    return whitespaceLexer.scan();
                }
            }
            
            // Fast comment detection without object creation
            if (c == '/' && position + 1 < input.length) {
                char next = input[position + 1];
                if (next == '/' || next == '*') {
                    if (skipComments) {
                        // Skip comment entirely
                        skipComment();
                        continue;
                    } else if (extractComments) {
                        // Record comment data without creating token
                        commentLexer.recordCommentPosition(position, line, column);
                        skipComment();
                        continue;
                    } else {
                        // Create comment token
                        return commentLexer.scan();
                    }
                }
            }
            
            // Significant token - must create
            tokenStartPos = position;
            tokenStartLine = line;
            tokenStartCol = column;
            
            if (c < 128) {
                if (CharClassifier.IS_DIGIT[c]) return numberLexer.scan();
                if (CharClassifier.IS_ID_START[c]) return identifierLexer.scan();
                if (c == '"' || c == '|') return stringLexer.scan();
                if (CharClassifier.IS_SYMBOL_START[c]) return symbolLexer.scan();
            }
            
            return createInvalidToken();
        }
        return null;
    }
    
    /**
     * Skip comment without creating any objects.
     * Updates position, line, column directly.
     */
    private void skipComment() {
        char[] arr = input;
        int pos = position;
        
        if (arr[pos] == '/' && pos + 1 < arr.length) {
            if (arr[pos + 1] == '/') {
                // Line comment - skip to newline
                pos += 2;
                while (pos < arr.length && arr[pos] != '\n') {
                    pos++;
                }
                position = pos;
                return;
            } else if (arr[pos + 1] == '*') {
                // Block comment - handle nesting
                pos += 2;
                int depth = 1;
                int currentLine = line;
                int currentCol = column + 2;
                
                while (pos < arr.length - 1 && depth > 0) {
                    if (arr[pos] == '/' && arr[pos + 1] == '*') {
                        depth++;
                        pos += 2;
                        currentCol += 2;
                    } else if (arr[pos] == '*' && arr[pos + 1] == '/') {
                        depth--;
                        pos += 2;
                        currentCol += 2;
                    } else {
                        if (arr[pos] == '\n') {
                            currentLine++;
                            currentCol = 1;
                        } else {
                            currentCol++;
                        }
                        pos++;
                    }
                }
                
                if (depth == 0) {
                    position = pos;
                    line = currentLine;
                    column = currentCol;
                }
            }
        }
    }
    
    /**
     * Fast path: get next token type without allocation.
     * Returns token type as int, or -1 if none.
     */
    public int peekNextTokenType() {
        int savedPos = position;
        int savedLine = line;
        int savedCol = column;
        
        try {
            while (position < input.length) {
                char c = input[position];
                if (c < 128 && CharClassifier.IS_WHITESPACE[c]) {
                    position++;
                    if (c == '\n') { line++; column = 1; }
                    else column++;
                    continue;
                }
                if (c == '/' && position + 1 < input.length) {
                    char next = input[position + 1];
                    if (next == '/' || next == '*') {
                        skipComment();
                        continue;
                    }
                }
                // Return token type as int
                if (CharClassifier.IS_DIGIT[c]) return TokenType.INT_LIT.ordinal();
                if (CharClassifier.IS_ID_START[c]) return TokenType.ID.ordinal();
                if (c == '"' || c == '|') return TokenType.TEXT_LIT.ordinal();
                if (CharClassifier.IS_SYMBOL_START[c]) return TokenType.SYMBOL.ordinal();
                return TokenType.INVALID.ordinal();
            }
            return TokenType.EOF.ordinal();
        } finally {
            position = savedPos;
            line = savedLine;
            column = savedCol;
        }
    }
    
    /**
     * Ultra-fast: check if next token is a specific type without allocation.
     */
    public boolean nextMatches(TokenType type) {
        return peekNextTokenType() == type.ordinal();
    }
    
    /**
     * Batch tokenization with reusable token list.
     */
    public void tokenizeInto(List<Token> reuseList) {
        reuseList.clear();
        boolean savedSkipWhitespace = skipWhitespace;
        boolean savedSkipComments = skipComments;
        
        // Temporarily disable skipping to get all tokens
        skipWhitespace = false;
        skipComments = false;
        
        try {
            while (position < input.length) {
                Token token = scanNextToken();
                if (token != null) {
                    reuseList.add(token);
                }
            }
            if (!isTemporary) {
                reuseList.add(createEOFToken());
            }
        } finally {
            skipWhitespace = savedSkipWhitespace;
            skipComments = savedSkipComments;
        }
    }
    
    /**
     * Stream processing - process tokens without storing them all.
     */
    public void processTokens(TokenProcessor processor) {
        boolean savedSkipWhitespace = skipWhitespace;
        boolean savedSkipComments = skipComments;
        
        // Don't skip when processing
        skipWhitespace = false;
        skipComments = false;
        
        try {
            while (position < input.length) {
                Token token = scanNextToken();
                if (token != null) {
                    processor.process(token);
                }
            }
            if (!isTemporary) {
                processor.process(createEOFToken());
            }
        } finally {
            skipWhitespace = savedSkipWhitespace;
            skipComments = savedSkipComments;
        }
    }
    
    /**
     * Get significant tokens only (skip whitespace and comments).
     */
    public List<Token> getSignificantTokens() {
        boolean savedSkipWhitespace = skipWhitespace;
        boolean savedSkipComments = skipComments;
        
        skipWhitespace = true;
        skipComments = true;
        
        try {
            return tokenize();
        } finally {
            skipWhitespace = savedSkipWhitespace;
            skipComments = savedSkipComments;
        }
    }
    
    public interface TokenProcessor {
        void process(Token token);
    }

    private Token createEOFToken() {
        return Token.createEOF(line, column);
    }

    private Token createInvalidToken() {
        int startLine = line;
        int startCol = column;
        int startPos = position;
        position++;
        column++;
        return new Token(TokenType.INVALID, input, startPos, 1, startLine, startCol, null, null, null, null);
    }

    // === LexerSource Implementation ===
    @Override 
    public char[] getInputArray() { 
        return input; 
    }
    
    @Override 
    public char[] getInput() { 
        return input; 
    }
    
    @Override 
    public int getPosition() { 
        return position; 
    }
    
    @Override 
    public void setPosition(int pos) { 
        this.position = pos; 
    }
    
    @Override 
    public int getLine() { 
        return line; 
    }
    
    @Override 
    public void setLine(int l) { 
        this.line = l; 
    }
    
    @Override 
    public int getColumn() { 
        return column; 
    }
    
    @Override 
    public void setColumn(int c) { 
        this.column = c; 
    }
    
    @Override 
    public char peek() { 
        return peek(0); 
    }
    
    @Override 
    public char peek(int offset) { 
        return (position + offset >= input.length) ? '\0' : input[position + offset]; 
    }
    
    @Override 
    public char consume() {
        char c = input[position++];
        if (c == '\n') { 
            line++; 
            column = 1; 
        } else { 
            column++; 
        }
        return c;
    }
    
    // === Utility Methods ===
    
    /**
     * Reset lexer to beginning of input.
     */
    public void reset() {
        position = 0;
        line = 1;
        column = 1;
        tokensCreated = 0;
        tokensSkipped = 0;
    }
    
    /**
     * Get current line content (for error messages).
     */
    public String getCurrentLine() {
        int start = position;
        while (start > 0 && input[start - 1] != '\n') start--;
        int end = position;
        while (end < input.length && input[end] != '\n') end++;
        return new String(input, start, end - start);
    }
    
    /**
     * Get position as SourceSpan (for error reporting).
     */
    public SourceSpan getCurrentSpan() {
        return new SourceSpan(position, line, column);
    }
    
    /**
     * Simple source span class for error reporting.
     */
    public static class SourceSpan {
        public final int position;
        public final int line;
        public final int column;
        
        public SourceSpan(int position, int line, int column) {
            this.position = position;
            this.line = line;
            this.column = column;
        }
    }
}