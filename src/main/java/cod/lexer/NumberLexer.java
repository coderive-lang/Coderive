package cod.lexer;

import java.util.*;

public class NumberLexer {

    private final LexerSource source;
    private final List<NumberValue> extractedNumbers;
    private boolean extractionMode = false;

    public NumberLexer(LexerSource source) {
        this.source = source;
        this.extractedNumbers = new ArrayList<NumberValue>();
    }

    public Token scan() {
        char[] input = source.getInputArray();
        int pos = source.getPosition();
        if (pos < input.length && input[pos] < 128 && CharClassifier.IS_DIGIT[input[pos]]) {
            return readNumber();
        }
        return null;
    }

    private Token readNumber() {
        int startLine = source.getLine();
        int startCol = source.getColumn();
        int startPos = source.getPosition();
        boolean isFloat = false;
        
        char[] input = source.getInputArray();
        int pos = startPos;

        while (pos < input.length && input[pos] < 128 && CharClassifier.IS_DIGIT[input[pos]]) pos++;

        if (pos < input.length && input[pos] == '.' && (pos + 1 >= input.length || input[pos + 1] != '.')) {
            isFloat = true;
            pos++;
            while (pos < input.length && input[pos] < 128 && CharClassifier.IS_DIGIT[input[pos]]) pos++;
        }
        
        if (pos < input.length && (input[pos] == 'e' || input[pos] == 'E')) {
            isFloat = true;
            pos++;
            if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) pos++;
            while (pos < input.length && input[pos] < 128 && CharClassifier.IS_DIGIT[input[pos]]) pos++;
        }

        if (pos < input.length) {
            char c = input[pos];
            if (c == 'K' || c == 'M' || c == 'B' || c == 'T') { isFloat = true; pos++; }
            else if (c == 'Q') {
                isFloat = true;
                pos++;
                if (pos < input.length && input[pos] == 'i') pos++;
            }
        }

        int length = pos - startPos;
        source.setPosition(pos);
        source.setColumn(startCol + length);

        if (extractionMode) {
            extractedNumbers.add(new NumberValue(new String(input, startPos, length), isFloat, startLine, startCol));
        }

        return Token.createNumber(input, startPos, length, isFloat, startLine, startCol);
    }

    public List<NumberValue> extractAllNumbers() {
        extractionMode = true;
        extractedNumbers.clear();
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
        return new ArrayList<NumberValue>(extractedNumbers);
    }

    public static class NumberValue {
        public final String text;
        public final boolean isFloat;
        public final int line;
        public final int column;
        public NumberValue(String text, boolean isFloat, int line, int column) {
            this.text = text; this.isFloat = isFloat; this.line = line; this.column = column;
        }
    }
}
