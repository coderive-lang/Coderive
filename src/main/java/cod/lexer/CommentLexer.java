package cod.lexer;

import java.util.*;

public class CommentLexer {

    private final LexerSource source;
    private final List<Comment> extractedComments;
    private boolean extractionMode = false;
    
    // === OPTIMIZATION: Recording mode for zero-allocation skipping ===
    private boolean recordingMode = false;
    private final List<CommentPosition> recordedPositions;
    
    public static class CommentPosition {
        public final int startPos;
        public final int line;
        public final int column;
        public final CommentType type;
        
        public CommentPosition(int startPos, int line, int column, CommentType type) {
            this.startPos = startPos;
            this.line = line;
            this.column = column;
            this.type = type;
        }
    }

    public CommentLexer(LexerSource source) {
        this.source = source;
        this.extractedComments = new ArrayList<Comment>();
        this.recordedPositions = new ArrayList<CommentPosition>();
    }

    public Token scan() {
        char[] input = source.getInputArray();
        int pos = source.getPosition();
        if (pos + 1 < input.length && input[pos] == '/') {
            if (input[pos + 1] == '/') return scanLineComment();
            if (input[pos + 1] == '*') return scanBlockComment();
        }
        return null;
    }
    
    /**
     * Record comment position without creating a token.
     * Used by MainLexer when skipComments=true but extractComments=false.
     */
    public void recordCommentPosition(int pos, int line, int column) {
        if (!recordingMode) return;
        
        char[] input = source.getInputArray();
        if (pos + 1 >= input.length) return;
        
        if (input[pos] == '/' && input[pos + 1] == '/') {
            recordedPositions.add(new CommentPosition(pos, line, column, CommentType.LINE));
        } else if (input[pos] == '/' && input[pos + 1] == '*') {
            recordedPositions.add(new CommentPosition(pos, line, column, CommentType.BLOCK));
        }
    }
    
    /**
     * Record comment with explicit type.
     */
    public void recordCommentPosition(int pos, int line, int column, CommentType type) {
        if (recordingMode) {
            recordedPositions.add(new CommentPosition(pos, line, column, type));
        }
    }
    
    /**
     * Start recording comment positions without creating tokens.
     */
    public void startRecording() {
        recordingMode = true;
        recordedPositions.clear();
    }
    
    /**
     * Stop recording and return recorded positions.
     */
    public List<CommentPosition> stopRecording() {
        recordingMode = false;
        return new ArrayList<CommentPosition>(recordedPositions);
    }
    
    /**
     * Fast comment skipping - updates source position without creating objects.
     * Returns true if a comment was found and skipped.
     */
    public boolean skipComment() {
        char[] input = source.getInputArray();
        int pos = source.getPosition();
        
        if (pos + 1 >= input.length) return false;
        if (input[pos] != '/') return false;
        
        if (input[pos + 1] == '/') {
            // Skip line comment
            int startPos = pos;
            pos += 2;
            while (pos < input.length && input[pos] != '\n') {
                pos++;
            }
            source.setPosition(pos);
            return true;
        } else if (input[pos + 1] == '*') {
            // Skip block comment with nesting support
            pos += 2;
            int depth = 1;
            int currentLine = source.getLine();
            int currentCol = source.getColumn() + 2;
            
            while (pos < input.length - 1 && depth > 0) {
                if (input[pos] == '/' && input[pos + 1] == '*') {
                    depth++;
                    pos += 2;
                    currentCol += 2;
                } else if (input[pos] == '*' && input[pos + 1] == '/') {
                    depth--;
                    pos += 2;
                    currentCol += 2;
                } else {
                    if (input[pos] == '\n') {
                        currentLine++;
                        currentCol = 1;
                    } else {
                        currentCol++;
                    }
                    pos++;
                }
            }
            
            source.setPosition(pos);
            source.setLine(currentLine);
            source.setColumn(currentCol);
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if current position is the start of a comment.
     */
    public boolean isComment() {
        char[] input = source.getInputArray();
        int pos = source.getPosition();
        return pos + 1 < input.length && 
               input[pos] == '/' && 
               (input[pos + 1] == '/' || input[pos + 1] == '*');
    }

    private Token scanLineComment() {
        int startLine = source.getLine();
        int startCol = source.getColumn();
        int startPos = source.getPosition();
        
        char[] input = source.getInputArray();
        int pos = startPos + 2; 
        
        while (pos < input.length && input[pos] != '\n') {
            pos++;
        }
        
        int length = pos - startPos;
        source.setPosition(pos);
        source.setColumn(source.getColumn() + length); 
        
        if (extractionMode) {
            extractedComments.add(new Comment(new String(input, startPos, length), startLine, startCol, CommentType.LINE));
        } else if (recordingMode) {
            recordedPositions.add(new CommentPosition(startPos, startLine, startCol, CommentType.LINE));
        }
        
        return new Token(TokenType.LINE_COMMENT, input, startPos, length, startLine, startCol, null, null, null, null);
    }

    private Token scanBlockComment() {
        int startLine = source.getLine();
        int startCol = source.getColumn();
        int startPos = source.getPosition();
        
        char[] input = source.getInputArray();
        int pos = startPos + 2; 
        int line = startLine;
        int col = startCol + 2;
        
        while (pos < input.length - 1) {
            if (input[pos] == '*' && input[pos + 1] == '/') {
                pos += 2;
                col += 2;
                break;
            }
            if (input[pos] == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
            pos++;
        }
        
        if (pos == input.length - 1 && !(input[pos-1] == '*' && input[pos] == '/')) {
           if (input[pos] == '\n') { line++; col=1; } else { col++; }
           pos++;
        }
        
        int length = pos - startPos;
        source.setPosition(pos);
        source.setLine(line);
        source.setColumn(col);
        
        if (extractionMode) {
            extractedComments.add(new Comment(new String(input, startPos, length), startLine, startCol, CommentType.BLOCK));
        } else if (recordingMode) {
            recordedPositions.add(new CommentPosition(startPos, startLine, startCol, CommentType.BLOCK));
        }
        
        return new Token(TokenType.BLOCK_COMMENT, input, startPos, length, startLine, startCol, null, null, null, null);
    }

    public List<Comment> extractAllComments() {
        extractionMode = true;
        extractedComments.clear();
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
        return new ArrayList<Comment>(extractedComments);
    }
    
    /**
     * Get recorded comment positions without extracting full comments.
     */
    public List<CommentPosition> getRecordedPositions() {
        return new ArrayList<CommentPosition>(recordedPositions);
    }
    
    /**
     * Clear recorded positions.
     */
    public void clearRecordedPositions() {
        recordedPositions.clear();
    }

    public void processWithoutComments(final CommentProcessor processor) {
        int savedPos = source.getPosition();
        int savedLine = source.getLine();
        int savedCol = source.getColumn();

        source.setPosition(0);
        source.setLine(1);
        source.setColumn(1);

        while (source.getPosition() < source.getInputArray().length) {
            Token token = scan();
            if (token == null) processor.process(String.valueOf(source.consume()), null);
        }

        source.setPosition(savedPos);
        source.setLine(savedLine);
        source.setColumn(savedCol);
    }

    public static class Comment {
        public final String text;
        public final int line;
        public final int column;
        public final CommentType type;

        public Comment(String text, int line, int column, CommentType type) {
            this.text = text; this.line = line; this.column = column; this.type = type;
        }
    }

    public enum CommentType { LINE, BLOCK }
    public interface CommentProcessor { void process(String text, CommentType type); }
}