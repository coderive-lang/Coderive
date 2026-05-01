package cod.lexer;

import java.util.ArrayList;
import java.util.List;

public class WhitespaceLexer {

    private final MainLexer lexer;
    private boolean recordingMode = false;
    private final List<WhitespaceInfo> recordedPositions;
    
    public static class WhitespaceInfo {
        public final int startPos;
        public final int line;
        public final int column;
        public final int length;
        
        public WhitespaceInfo(int startPos, int line, int column, int length) {
            this.startPos = startPos;
            this.line = line;
            this.column = column;
            this.length = length;
        }
    }

    public WhitespaceLexer(MainLexer lexer) {
        this.lexer = lexer;
        this.recordedPositions = new ArrayList<WhitespaceInfo>();
    }
    
    public void startRecording() {
        recordingMode = true;
        recordedPositions.clear();
    }
    
    public List<WhitespaceInfo> stopRecording() {
        recordingMode = false;
        return new ArrayList<WhitespaceInfo>(recordedPositions);
    }
    
    public void recordWhitespacePosition(int pos, int line, int col) {
        if (recordingMode) {
            // Need to get length - scan forward
            int length = 0;
            int tempPos = pos;
            char[] input = lexer.getInputArray();
            while (tempPos < input.length) {
                char c = input[tempPos];
                if (c >= 128 || !CharClassifier.IS_WHITESPACE[c]) break;
                length++;
                tempPos++;
            }
            recordedPositions.add(new WhitespaceInfo(pos, line, col, length));
        }
    }
    
    public void clearRecordedPositions() {
        recordedPositions.clear();
    }
    
    public List<WhitespaceInfo> getRecordedPositions() {
        return new ArrayList<WhitespaceInfo>(recordedPositions);
    }

    public Token scan() {
        char c = lexer.peek();
        if (c < 128 && CharClassifier.IS_WHITESPACE[c]) {
            return scanWhitespace();
        }
        return null;
    }

    private Token scanWhitespace() {
        int startLine = lexer.getLine();
        int startCol = lexer.getColumn();
        int startPos = lexer.getPosition();
        int length = 0;

        while (lexer.getPosition() < lexer.getInput().length) {
            char c = lexer.peek();
            if (c >= 128 || !CharClassifier.IS_WHITESPACE[c]) break;
            lexer.consume();
            length++;
        }

        char[] source = lexer.getInputArray();
        
        if (recordingMode) {
            recordedPositions.add(new WhitespaceInfo(startPos, startLine, startCol, length));
        }
        
        return Token.createWhitespace(source, startPos, length, startLine, startCol);
    }

    public boolean isAtWhitespace() {
        char c = lexer.peek();
        return c < 128 && CharClassifier.IS_WHITESPACE[c];
    }
}