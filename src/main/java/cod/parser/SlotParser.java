package cod.parser;

import cod.ast.ASTFactory;
import cod.ast.node.*;
import cod.lexer.Token;
import static cod.lexer.TokenType.*;
import static cod.lexer.TokenType.Symbol.*;
import java.util.ArrayList;
import java.util.List;

public class SlotParser {
    
    private final BaseParser parser;
    private final ExpressionParser exprParser;
    
    public SlotParser(ExpressionParser parser) {
        this.parser = parser;
        this.exprParser = parser;
    }
    
    public SlotParser(StatementParser parser) {
        this.parser = parser;
        this.exprParser = parser.expressionParser;
    }
    
    public SlotParser(DeclarationParser parser) {
        this.parser = parser;
        this.exprParser = parser.getStatementParser().expressionParser;
    }
    
    /**
     * Predictive Slot Contract parsing (LL(0..2)).
     */
    public List<Slot> parseSlotContract() {
        parser.expect(DOUBLE_COLON);
        List<Slot> slots = new ArrayList<Slot>();
        
        boolean isNamedMode = false;
        int index = 0;
        
        // Use LL(0..2) window check to decide the parsing style
        if (parser.match(ID, COLON)) {
            isNamedMode = true;
        }

        do {
            String name;
            String type;
            Token nameToken = null;
            
            if (isNamedMode) {
                nameToken = parser.now();
                name = parser.expect(ID).getText();
                parser.expect(COLON);
                type = parser.parseTypeReference();
            } else {
                name = String.valueOf(index);
                type = parser.parseTypeReference();
            }
            
            slots.add(ASTFactory.createSlot(type, name, nameToken));
            index++;
            
        } while (parser.consume(COMMA));
        
        return slots;
    }

    /**
     * LL(0..2) Slot Assignment check.
     */
    public SlotAssignment parseSingleSlotAssignment() {
        String slotName = null;
        Expr value;
        Token colonToken = null;
        
        // Predictive check: Is it 'name: value' or just 'value'?
        if (parser.match(ID, COLON)) {
            slotName = parser.expect(ID).getText();
            colonToken = parser.now();
            parser.expect(COLON);
            value = exprParser.parseExpr();
        } else {
            value = exprParser.parseExpr();
        }
        
        return ASTFactory.createSlotAsmt(slotName, value, colonToken);
    }

    public List<SlotAssignment> parseSlotAssignments() {
        List<SlotAssignment> assignments = new ArrayList<SlotAssignment>();
        assignments.add(parseSingleSlotAssignment());
        while (parser.consume(COMMA)) {
            assignments.add(parseSingleSlotAssignment());
        }
        return assignments;
    }

    public List<SlotAssignment> parseParenthesizedSlotAssignments(Token tildeArrowToken) {
        parser.expect(LPAREN);
        if (parser.is(RPAREN)) {
            throw parser.error("~> requires at least one return value assignment", tildeArrowToken);
        }
        List<SlotAssignment> assignments = parseSlotAssignments();
        parser.expect(RPAREN);
        return assignments;
    }
    
    public Stmt parseSlotAssignmentsAsStmt(Token tildeArrowToken) {
        List<SlotAssignment> assignments = parseParenthesizedSlotAssignments(tildeArrowToken);
        if (assignments.size() == 1) {
            return assignments.get(0);
        }
        return ASTFactory.createMultipleSlotAsmt(assignments, tildeArrowToken);
    }
    
    public void validateSlotCount(List<Slot> contract, List<SlotAssignment> assignments, Token errorToken) {
        if (contract != null && !contract.isEmpty() && contract.size() != assignments.size()) {
            throw parser.error("Slot contract expects " + contract.size() + 
                " values, but " + assignments.size() + " provided.", errorToken);
        }
    }
}