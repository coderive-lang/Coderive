package cod.lexer;

public final class CharClassifier {
    
    public static final boolean[] IS_WHITESPACE = new boolean[128];
    public static final boolean[] IS_DIGIT = new boolean[128];
    public static final boolean[] IS_ID_START = new boolean[128];
    public static final boolean[] IS_ID_PART = new boolean[128];
    public static final boolean[] IS_HEX = new boolean[128];
    public static final boolean[] IS_SYMBOL_START = new boolean[128];
    
    static {
        // Whitespace
        IS_WHITESPACE[' '] = true;
        IS_WHITESPACE['\t'] = true;
        IS_WHITESPACE['\n'] = true;
        IS_WHITESPACE['\r'] = true;
        IS_WHITESPACE['\f'] = true;
        
        // Digits
        for (char c = '0'; c <= '9'; c++) {
            IS_DIGIT[c] = true;
            IS_ID_PART[c] = true;
            IS_HEX[c] = true;
        }
        
        // Hex letters
        for (char c = 'a'; c <= 'f'; c++) IS_HEX[c] = true;
        for (char c = 'A'; c <= 'F'; c++) IS_HEX[c] = true;
        
        // Identifier start (letters and underscore)
        for (char c = 'a'; c <= 'z'; c++) {
            IS_ID_START[c] = true;
            IS_ID_PART[c] = true;
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            IS_ID_START[c] = true;
            IS_ID_PART[c] = true;
        }
        IS_ID_START['_'] = true;
        IS_ID_PART['_'] = true;
        
        // Symbol start characters
        String symbols = "|&?$%,(){}[]_\\#=><!+-*/~.:";
        for (char c : symbols.toCharArray()) {
            IS_SYMBOL_START[c] = true;
        }
    }
    
    private CharClassifier() {}
}