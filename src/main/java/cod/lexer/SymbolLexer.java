package cod.lexer;

import cod.lexer.TokenType.Symbol;
import static cod.lexer.TokenType.Symbol.*;
import java.util.*;

public class SymbolLexer {

    private final LexerSource source;
    private static final int _START = 0;
    private static final int _COLON = 1;
    private static final int _COLON_COLON = 2;
    private static final int _COLON_EQUALS = 3;
    private static final int _EQUALS = 4;
    private static final int _EQUALS_EQUALS = 5;
    private static final int _GREATER = 6;
    private static final int _GREATER_EQUALS = 7;
    private static final int _LESS = 8;
    private static final int _LESS_EQUALS = 9;
    private static final int _BANG = 10;
    private static final int _BANG_EQUALS = 11;
    private static final int _PLUS = 12;
    private static final int _PLUS_EQUALS = 13;
    private static final int _MINUS = 14;
    private static final int _MINUS_EQUALS = 15;
    private static final int _MUL = 16;
    private static final int _MUL_EQUALS = 17;
    private static final int _DIV = 18;
    private static final int _DIV_EQUALS = 19;
    private static final int _TILDE = 20;
    private static final int _TILDE_ARROW = 21;
    private static final int _DOT = 22;
    private static final int _DOT_DOT = 23;
    
    private static final int MAX_STATE = 30;
    private static final int[][] TRANSITION = new int[MAX_STATE][128];
    private static final Symbol[] ACCEPT = new Symbol[MAX_STATE];
    private static final Symbol[] SINGLE_CHAR_SYMBOLS = new Symbol[128];
    
    static {
        for (int i = 0; i < MAX_STATE; i++) Arrays.fill(TRANSITION[i], -1);
        SINGLE_CHAR_SYMBOLS['|'] = PIPE;
        SINGLE_CHAR_SYMBOLS['&'] = AMPERSAND;
        SINGLE_CHAR_SYMBOLS['?'] = QUESTION;
        SINGLE_CHAR_SYMBOLS['$'] = DOLLAR;
        SINGLE_CHAR_SYMBOLS['%'] = MOD;
        SINGLE_CHAR_SYMBOLS[','] = COMMA;
        SINGLE_CHAR_SYMBOLS['('] = LPAREN;
        SINGLE_CHAR_SYMBOLS[')'] = RPAREN;
        SINGLE_CHAR_SYMBOLS['{'] = LBRACE;
        SINGLE_CHAR_SYMBOLS['}'] = RBRACE;
        SINGLE_CHAR_SYMBOLS['['] = LBRACKET;
        SINGLE_CHAR_SYMBOLS[']'] = RBRACKET;
        SINGLE_CHAR_SYMBOLS['_'] = UNDERSCORE;
        SINGLE_CHAR_SYMBOLS['\\'] = LAMBDA;
        SINGLE_CHAR_SYMBOLS['#'] = RANGE_HASH;
        
        TRANSITION[_START][':'] = _COLON;
        TRANSITION[_START]['='] = _EQUALS;
        TRANSITION[_START]['>'] = _GREATER;
        TRANSITION[_START]['<'] = _LESS;
        TRANSITION[_START]['!'] = _BANG;
        TRANSITION[_START]['+'] = _PLUS;
        TRANSITION[_START]['-'] = _MINUS;
        TRANSITION[_START]['*'] = _MUL;
        TRANSITION[_START]['/'] = _DIV;
        TRANSITION[_START]['~'] = _TILDE;
        TRANSITION[_START]['.'] = _DOT;
        
        TRANSITION[_COLON][':'] = _COLON_COLON; ACCEPT[_COLON_COLON] = DOUBLE_COLON;
        TRANSITION[_COLON]['='] = _COLON_EQUALS; ACCEPT[_COLON_EQUALS] = DOUBLE_COLON_ASSIGN;
        ACCEPT[_COLON] = COLON;
        TRANSITION[_EQUALS]['='] = _EQUALS_EQUALS; ACCEPT[_EQUALS_EQUALS] = EQ;
        ACCEPT[_EQUALS] = ASSIGN;
        TRANSITION[_GREATER]['='] = _GREATER_EQUALS; ACCEPT[_GREATER_EQUALS] = GTE;
        ACCEPT[_GREATER] = GT;
        TRANSITION[_LESS]['='] = _LESS_EQUALS; ACCEPT[_LESS_EQUALS] = LTE;
        ACCEPT[_LESS] = LT;
        TRANSITION[_BANG]['='] = _BANG_EQUALS; ACCEPT[_BANG_EQUALS] = NEQ;
        ACCEPT[_BANG] = BANG;
        TRANSITION[_PLUS]['='] = _PLUS_EQUALS; ACCEPT[_PLUS_EQUALS] = PLUS_ASSIGN;
        ACCEPT[_PLUS] = PLUS;
        TRANSITION[_MINUS]['='] = _MINUS_EQUALS; ACCEPT[_MINUS_EQUALS] = MINUS_ASSIGN;
        ACCEPT[_MINUS] = MINUS;
        TRANSITION[_MUL]['='] = _MUL_EQUALS; ACCEPT[_MUL_EQUALS] = MUL_ASSIGN;
        ACCEPT[_MUL] = MUL;
        TRANSITION[_DIV]['='] = _DIV_EQUALS; ACCEPT[_DIV_EQUALS] = DIV_ASSIGN;
        ACCEPT[_DIV] = DIV;
        TRANSITION[_TILDE]['>'] = _TILDE_ARROW; ACCEPT[_TILDE_ARROW] = TILDE_ARROW;
        ACCEPT[_TILDE] = TILDE_ARROW;
        TRANSITION[_DOT]['.'] = _DOT_DOT; ACCEPT[_DOT_DOT] = RANGE_DOTDOT;
        ACCEPT[_DOT] = DOT;
    }

    public SymbolLexer(LexerSource source) { this.source = source; }

    public Token scan() {
        char[] input = source.getInputArray();
        int pos = source.getPosition();
        if (pos >= input.length) return null;
        
        char first = input[pos];
        if (first >= 128 || !CharClassifier.IS_SYMBOL_START[first]) return null;
        
        int startLine = source.getLine();
        int startCol = source.getColumn();
        int startPos = pos;
        int state = _START;
        int lastAcceptPos = -1;
        Symbol lastAcceptSymbol = null;
        
        while (pos < input.length) {
            char c = input[pos];
            if (c >= 128) break;
            int nextState = TRANSITION[state][c];
            if (nextState == -1) break;
            state = nextState;
            pos++;
            if (ACCEPT[state] != null) { lastAcceptPos = pos; lastAcceptSymbol = ACCEPT[state]; }
        }
        
        if (lastAcceptPos != -1) {
            int length = lastAcceptPos - startPos;
            source.setPosition(lastAcceptPos);
            source.setColumn(startCol + length);
            return Token.createSymbol(input, startPos, length, startLine, startCol, lastAcceptSymbol);
        }
        
        if (first < 128 && SINGLE_CHAR_SYMBOLS[first] != null) {
            source.setPosition(startPos + 1);
            source.setColumn(startCol + 1);
            return Token.createSymbol(input, startPos, 1, startLine, startCol, SINGLE_CHAR_SYMBOLS[first]);
        }
        return null;
    }
}
