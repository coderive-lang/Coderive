package cod.lexer;

/**
 * Provides an independent buffer and state management contract for sub-lexers.
 */
public interface LexerSource {
    char[] getInputArray();
    char[] getInput(); // Required for StringLexer/Interpolation logic
    
    int getPosition();
    void setPosition(int pos);
    
    int getLine();
    void setLine(int line);
    
    int getColumn();
    void setColumn(int column);
    
    char peek();
    char peek(int offset);
    char consume();
}
