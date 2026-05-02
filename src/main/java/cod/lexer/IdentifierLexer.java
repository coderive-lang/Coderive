package cod.lexer;

import cod.lexer.TokenType.Keyword;
import cod.error.LexError;
import java.util.*;

public class IdentifierLexer {

    private final LexerSource source;
    private final List<String> extractedIdentifiers;
    private final Set<String> keywords;
    private boolean extractionMode = false;
    
    private static final int[] KEYWORD_HASH = new int[512];
    private static final Keyword[] KEYWORD_BY_HASH = new Keyword[512];
    
    static {
        for (Keyword kw : Keyword.values()) {
            String name = kw.toString();
            int hash = perfectHash(name);
            if (KEYWORD_HASH[hash] == 1) throw new LexError("FATAL: Hash collision in keyword lexer!");
            KEYWORD_HASH[hash] = 1;
            KEYWORD_BY_HASH[hash] = kw;
        }
    }
    
    private static int perfectHash(String s) {
        int len = s.length();
        if (len == 0) return 0;
        int hash = len * 31;
        hash = hash * 31 + s.charAt(0) * 17;
        hash = hash * 31 + s.charAt(len - 1) * 13;
        if (len > 2) hash = hash * 31 + s.charAt(len / 2) * 7;
        int rolling = 0;
        for (int i = 0; i < len; i++) rolling = (rolling << 5) - rolling + s.charAt(i);
        hash = (hash ^ (rolling & 0x1FF)) & 511;
        return hash;
    }

    public IdentifierLexer(LexerSource source) {
        this.source = source;
        this.extractedIdentifiers = new ArrayList<String>();
        this.keywords = new HashSet<String>();
        for (Keyword keyword : Keyword.values()) keywords.add(keyword.toString());
    }

    public Token scan() {
        char[] input = source.getInputArray();
        int pos = source.getPosition();
        if (pos < input.length && input[pos] < 128 && CharClassifier.IS_ID_START[input[pos]]) {
            return readIdentifierOrKeyword();
        }
        return null;
    }

    private Token readIdentifierOrKeyword() {
        int startLine = source.getLine();
        int startCol = source.getColumn();
        int startPos = source.getPosition();
        
        char[] input = source.getInputArray();
        int pos = startPos;
        int rolling = 0;
        
        while (pos < input.length) {
            char c = input[pos];
            if (c >= 128 || !CharClassifier.IS_ID_PART[c]) break;
            rolling = (rolling << 5) - rolling + c;
            pos++;
        }
        
        int length = pos - startPos;
        source.setPosition(pos);
        source.setColumn(startCol + length);
        
        int hash = (length * 31);
        hash = hash * 31 + input[startPos] * 17;
        hash = hash * 31 + input[pos - 1] * 13;
        if (length > 2) hash = hash * 31 + input[startPos + (length / 2)] * 7;
        hash = (hash ^ (rolling & 0x1FF)) & 511;

        if (KEYWORD_HASH[hash] == 1) {
            Keyword keyword = KEYWORD_BY_HASH[hash];
            if (matchesExactly(input, startPos, length, keyword.toString())) {
                return Token.createKeyword(input, startPos, length, startLine, startCol, keyword);
            }
        }

        if (extractionMode) extractedIdentifiers.add(new String(input, startPos, length));
        return Token.createIdentifier(input, startPos, length, startLine, startCol);
    }
    
    private boolean matchesExactly(char[] input, int start, int length, String keyword) {
        if (length != keyword.length()) return false;
        for (int i = 0; i < length; i++) if (input[start + i] != keyword.charAt(i)) return false;
        return true;
    }

    public List<String> extractAllIdentifiers() {
        extractionMode = true;
        extractedIdentifiers.clear();
        int savedPos = source.getPosition();
        int savedLine = source.getLine();
        int savedCol = source.getColumn();

        source.setPosition(0);
        source.setLine(1);
        source.setColumn(1);

        while (source.getPosition() < source.getInputArray().length) {
            Token token = scan();
            if (token == null) source.consume();
        }

        source.setPosition(savedPos);
        source.setLine(savedLine);
        source.setColumn(savedCol);
        extractionMode = false;
        return new ArrayList<String>(extractedIdentifiers);
    }
}
