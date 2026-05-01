package cod.parser;

import cod.ast.ASTFactory;
import cod.error.ParseError;
import cod.ast.node.*;
import cod.interpreter.registry.GlobalRegistry;
import cod.lexer.Token;
import cod.semantic.NamingValidator;
import static cod.lexer.TokenType.*;
import cod.math.AutoStackingNumber;
import cod.parser.context.*;
import static cod.lexer.TokenType.Symbol.*;
import static cod.lexer.TokenType.Keyword.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ExpressionParser extends BaseParser {
    private static final String SELF_CALL_PLACEHOLDER = "<~";
    
    private static final class UnicodeEscapeParseResult {
        private final String text;
        private final int nextPos;
        private UnicodeEscapeParseResult(String text, int nextPos) {
            this.text = text;
            this.nextPos = nextPos;
        }
    }
    
    private static final int PREC_ASSIGNMENT = 10;
    private static final int PREC_EQUALITY = 50;
    private static final int PREC_CHAIN = 55;
    private static final int PREC_COMPARISON = 60;
    private static final int PREC_TERM = 70;
    private static final int PREC_FACTOR = 80;
    private static final int PREC_UNARY = 90;
    private static final int PREC_CALL = 100;
    private static final int PREC_IS = 40;
    private static final int[] REDUCTION_PRECEDENCE_ORDER = {
        PREC_FACTOR, PREC_TERM, PREC_COMPARISON, PREC_EQUALITY, PREC_IS
    };
    
    private final GlobalRegistry globalRegistry;
    private Set<String> globalFunctionNames;
    private StatementParser statementParser;
    private int bareInferredLambdaDisabledDepth = 0;
    
    public ExpressionParser(ParserContext ctx) {
        this(ctx, null, null);
    }
    
    public ExpressionParser(ParserContext ctx, GlobalRegistry globalRegistry) {
        this(ctx, globalRegistry, null);
    }
    
    public ExpressionParser(ParserContext ctx, GlobalRegistry globalRegistry, StatementParser statementParser) {
        super(ctx);
        this.globalRegistry = globalRegistry;
        this.statementParser = statementParser;
        if (globalRegistry != null) {
            this.globalFunctionNames = globalRegistry.getGlobalFunctionNames();
        }
    }
    
    public void setStatementParser(StatementParser statementParser) {
        this.statementParser = statementParser;
    }
    
    @Override
    protected BaseParser createIsolatedParser(ParserContext isolatedCtx) {
        return new ExpressionParser(isolatedCtx, this.globalRegistry, this.statementParser);
    }

    public Expr parseExpr() {
        if (bareInferredLambdaDisabledDepth == 0) {
            if (is(DOLLAR)) {
                Expr inferredLambdaExpr = tryParseBareInferredLambdaExpression();
                if (inferredLambdaExpr != null) {
                    return inferredLambdaExpr;
                }
            }
        }
        if (is(ALL, ANY)) {
            Token nextToken = next();
            if (is(nextToken, LPAREN)) {
                return parseMethodCall();
            }
            return parseBooleanChain();
        }
        return parsePrecedence(PREC_ASSIGNMENT);
    }

    private Expr parseConstructorCall() {
        Token classNameToken = now();
        String className = expect(ID).getText();
        expect(LPAREN);

        List<Expr> args = new ArrayList<Expr>();
        List<String> argNames = new ArrayList<String>();
        
        if (!is(RPAREN)) {
            if (isNamedArgument()) {
                parseNamedArgumentList(args, argNames);
            } else {
                args.add(parseExpr());
                argNames.add(null);
                while (consume(COMMA)) {
                    args.add(parseExpr());
                    argNames.add(null);
                }
            }
        }
        expect(RPAREN);
        
        ConstructorCall call = ASTFactory.createConstructorCall(className, args, classNameToken);
        call.argNames = argNames;
        return call;
    }

    private boolean isNamedArgument() {
        Token first = now();
        if (!is(first, ID)) return false;
        return is(next(), COLON);
    }

    private void parseNamedArgumentList(List<Expr> args, List<String> argNames) {
        do {
            String argName = expect(ID).getText();
            expect(COLON);
            Expr value = parseExpr();
            
            args.add(value);
            argNames.add(argName);
            
            if (!is(COMMA)) break;
            expect(COMMA);
        } while (!is(RPAREN));
    }

    public MethodCall parseMethodCall() {
        Token nameStartToken = now();
        String qualifiedNameStr = parseQualifiedNameOrKeyword();
        String methodName = qualifiedNameStr;
        if (qualifiedNameStr.contains(".")) {
            methodName = qualifiedNameStr.substring(qualifiedNameStr.lastIndexOf('.') + 1);
        }
        
        MethodCall call = ASTFactory.createMethodCall(methodName, qualifiedNameStr, nameStartToken);
        
        if (!qualifiedNameStr.contains(".") && globalFunctionNames != null && 
            globalFunctionNames.contains(methodName)) {
            call.isGlobal = true;
        }
        
        expect(LPAREN);
        if (!is(RPAREN)) {
            if (isNamedArgument()) {
                parseNamedArgumentList(call.arguments, call.argNames);
            } else {
                call.arguments.add(parseExpr());
                call.argNames.add(null);
                while (consume(COMMA)) {
                    call.arguments.add(parseExpr());
                    call.argNames.add(null);
                }
            }
        }
        expect(RPAREN);
        return call;
    }

    private MethodCall parseSuperMethodCall() {
        Token superToken = now();
        expect(SUPER);
        expect(DOT);
        
        Token methodToken = now();
        String methodName;
        
        if (is(methodToken, ID)) {
            methodName = expect(ID).getText();
        } else if (canBeMethod(methodToken)) {
            methodName = expect(KEYWORD).getText();
        } else {
            throw error("Expected method name after 'super.'", methodToken);
        }
        
        MethodCall call = ASTFactory.createMethodCall(methodName, "super." + methodName, superToken);
        call.isSuperCall = true;
        call.isGlobal = false;
        
        expect(LPAREN);
        if (!is(RPAREN)) {
            if (isNamedArgument()) {
                parseNamedArgumentList(call.arguments, call.argNames);
            } else {
                call.arguments.add(parseExpr());
                call.argNames.add(null);
                while (consume(COMMA)) {
                    call.arguments.add(parseExpr());
                    call.argNames.add(null);
                }
            }
        }
        expect(RPAREN);
        return call;
    }

    private boolean isSuperMethodCall() {
        if (!is(now(), SUPER)) return false;
        if (!is(next(1), DOT)) return false;
        Token nameToken = next(2);
        if (!(is(nameToken, ID) || canBeMethod(nameToken))) return false;
        return is(next(3), LPAREN);
    }

    private String parseQualifiedNameOrKeyword() {
        Token token = now();
        if (canBeMethod(token)) {
            String name = expect(KEYWORD).getText();
            if (consume(DOT)) {
                StringBuilder fullName = new StringBuilder(name);
                fullName.append(".");
                fullName.append(expect(ID).getText());
                while (consume(DOT)) {
                    fullName.append(".");
                    fullName.append(expect(ID).getText());
                }
                return fullName.toString();
            }
            return name;
        }
        return parseQualifiedName();
    }

    public IndexAccess parseIndexAccessContinuation(Expr arrayExpr) {
        Token lbracketToken = expect(LBRACKET);
        Expr firstExpr = parseExpr();
        Expr indexExpr;
        
        if (is(RANGE_DOTDOT) || is(TO)) {
            Token rangeToken = consume();
            Expr end = parseExpr();
            Expr step = null;
            Token stepToken = null;
            if (is(RANGE_HASH) || is(BY)) {
                stepToken = consume();
                step = parseExpr();
            }
            RangeIndex ri = ASTFactory.createRangeIndex(step, firstExpr, end, stepToken, rangeToken);
            
            if (is(COMMA)) {
                List<RangeIndex> ranges = new ArrayList<RangeIndex>();
                ranges.add(ri);
                while (consume(COMMA)) {
                    ranges.add(parseSingleRangeIndex());
                }
                expect(RBRACKET);
                indexExpr = ASTFactory.createMultiRangeIndex(ranges, null);
            } else {
                expect(RBRACKET);
                indexExpr = ri;
            }
        } else {
            if (consume(COMMA)) {
                List<Expr> indices = new ArrayList<Expr>();
                indices.add(firstExpr);
                indices.add(parseExpr());
                while (consume(COMMA)) {
                    indices.add(parseExpr());
                }
                expect(RBRACKET);
                indexExpr = ASTFactory.createTuple(indices, lbracketToken);
            } else {
                expect(RBRACKET);
                indexExpr = firstExpr;
            }
        }
        return ASTFactory.createIndexAccess(arrayExpr, indexExpr, lbracketToken);
    }

    private RangeIndex parseSingleRangeIndex() {
        Expr start = parseExpr();
        Token rangeToken = null;
        if (is(RANGE_DOTDOT)) {
            rangeToken = expect(RANGE_DOTDOT);
        } else if (is(TO)) {
            rangeToken = expect(TO);
        } else {
            throw error("Expected range operator '..' or 'to'");
        }
        
        Expr end = parseExpr();
        Expr step = null;
        Token stepToken = null;
        if (is(RANGE_HASH)) {
            stepToken = expect(RANGE_HASH);
            step = parseExpr();
        } else if (is(BY)) {
            stepToken = expect(BY);
            step = parseExpr();
        }
        return ASTFactory.createRangeIndex(step, start, end, stepToken, rangeToken);
    }

    public List<String> parseReturnSlots() {
        expect(LBRACKET);
        List<String> slots = new ArrayList<String>();
        do {
            if (is(ID)) {
                slots.add(expect(ID).getText());
            } else if (is(INT_LIT)) {
                slots.add(expect(INT_LIT).getText());
            } else {
                throw error("Expected slot name or index", now());
            }
        } while (consume(COMMA));
        expect(RBRACKET);
        return slots;
    }
    
    private Expr parseIfExpr() {
        Token ifToken = expect(IF);
        Expr condition = parseExpr();
        
        Expr thenExpr;
        if (is(LBRACE)) {
            expect(LBRACE);
            thenExpr = parseExpr();
            expect(RBRACE);
        } else {
            thenExpr = parseExpr();
        }
        
        Token elseToken = expect(ELSE);
        Expr elseExpr;
        if (is(LBRACE)) {
            expect(LBRACE);
            elseExpr = parseExpr();
            expect(RBRACE);
        } else {
            elseExpr = parseExpr();
        }
        return ASTFactory.createIfExpr(condition, thenExpr, elseExpr, ifToken, elseToken);
    }

    public boolean isLambdaExpression() {
        return is(LAMBDA);
    }

    public Lambda parseLambdaSignature() {
        Token lambdaToken = expect(LAMBDA);
        SlotParser slotParser = new SlotParser(this);
        expect(LPAREN);
        
        LambdaParamsParseResult lambdaParams = parseLambdaParameters();
        List<Param> parameters = lambdaParams.parameters;
        expect(RPAREN);
        
        if (!is(DOUBLE_COLON) && !is(TILDE_ARROW) && !is(LBRACE)) {
            Lambda lambda = ASTFactory.createLambda(parameters, null, null, lambdaToken);
            lambda.inferParameters = lambdaParams.inferParameters;
            lambda.expressionBody = parseExprWithoutBareInferredLambda();
            return lambda;
        }
        
        List<Slot> returnSlots = null;
        if (is(DOUBLE_COLON)) {
            returnSlots = slotParser.parseSlotContract();
        }
        
        Stmt body;
        Token tildeArrowToken = null;
        
        if (is(LBRACE)) {
            if (statementParser == null) {
                throw error("Internal error: statementParser not available for lambda block");
            }
            expect(LBRACE);
            Block block = new Block();
            while (!is(RBRACE) && !is(EOF)) {
                block.statements.add(statementParser.parseStmt());
            }
            expect(RBRACE);
            body = block;
        } else if (is(TILDE_ARROW)) {
            tildeArrowToken = expect(TILDE_ARROW);
            List<SlotAssignment> assignments = slotParser.parseParenthesizedSlotAssignments(tildeArrowToken);

            if (returnSlots != null) {
                slotParser.validateSlotCount(returnSlots, assignments, tildeArrowToken);
            }

            Block block = new Block();
            if (assignments.size() == 1) {
                block.statements.add(assignments.get(0));
            } else {
                block.statements.add(ASTFactory.createMultipleSlotAsmt(assignments, tildeArrowToken));
            }
            body = block;
        } else {
            throw error("Expected '~>' or '{' after lambda parameters", now());
        }
        
        Lambda lambda = ASTFactory.createLambda(parameters, returnSlots, body, lambdaToken);
        lambda.inferParameters = lambdaParams.inferParameters;
        return lambda;
    }

    private LambdaParamsParseResult parseLambdaParameters() {
        List<Param> parameters = new ArrayList<Param>();
        boolean inferParameters = false;

        if (!is(RPAREN)) {
            Token current = now();
            boolean underscoreInferMarker = (is(UNDERSCORE) || (is(current, ID) && "_".equals(current.getText()))) && is(next(), RPAREN);
            if (underscoreInferMarker) {
                consume();
                inferParameters = true;
            } else {
                parameters.add(parseLambdaParameter());
                while (consume(COMMA)) {
                    parameters.add(parseLambdaParameter());
                }
            }
        }
        return new LambdaParamsParseResult(parameters, inferParameters);
    }

    private Expr tryParseBareInferredLambdaExpression() {
        if (!is(DOLLAR)) return null;
        Token lambdaToken = now();
        Expr expressionBody = parsePrecedence(PREC_ASSIGNMENT);
        Lambda lambda = ASTFactory.createLambda(new ArrayList<Param>(), null, null, lambdaToken);
        lambda.inferParameters = true;
        lambda.expressionBody = expressionBody;
        return lambda;
    }

    private Expr parseExprWithoutBareInferredLambda() {
        bareInferredLambdaDisabledDepth++;
        try {
            return parseExpr();
        } finally {
            bareInferredLambdaDisabledDepth--;
        }
    }

    private static final class LambdaParamsParseResult {
        private final List<Param> parameters;
        private final boolean inferParameters;
        private LambdaParamsParseResult(List<Param> parameters, boolean inferParameters) {
            this.parameters = parameters;
            this.inferParameters = inferParameters;
        }
    }

    private Param parseLambdaParameter() {
        Token paramToken = now();
        if (is(LPAREN)) {
            expect(LPAREN);
            List<String> tupleElements = new ArrayList<String>();
            if (!is(RPAREN)) {
                tupleElements.add(expect(ID).getText());
                while (consume(COMMA)) tupleElements.add(expect(ID).getText());
            }
            expect(RPAREN);
            Param param = ASTFactory.createParam("_tuple", null, null, true, paramToken);
            param.isTupleDestructuring = true;
            param.tupleElements = tupleElements;
            param.isLambdaParameter = true;
            return param;
        }
        
        String paramName = expect(ID).getText();
        String paramType = null;
        if (consume(COLON)) {
            paramType = parseTypeReference();
        }
        
        Expr defaultValue = null;
        if (consume(ASSIGN)) {
            defaultValue = parseExpr();
        }
        
        boolean typeInferred = (paramType == null);
        Param param = ASTFactory.createParam(paramName, paramType, defaultValue, typeInferred, paramToken);
        param.isLambdaParameter = true;
        param.hasDefaultValue = (defaultValue != null);
        return param;
    }

    public Expr parsePrimaryExpr() {
        Expr baseExpr;
        Token startToken = now();
        
        if (startToken == null) throw error("Unexpected end of input in primary expression");
        
        if (is(LAMBDA)) {
            baseExpr = parseLambdaSignature();
        }
        else if (isSuperMethodCall()) {
            baseExpr = parseSuperMethodCall();
        }
        else if (is(SUPER)) {
            Token superToken = expect(SUPER);
            baseExpr = ASTFactory.createSuperExpr(superToken);
        }
        else if (isThisKeyword()) {
            baseExpr = parseThisExpr();
        }
        else if (isConstructorCall() && !isMethodCallFollows()) {
            baseExpr = parseConstructorCall();
        }
        else if (is(LBRACKET)) {
            if (isSlotAccessExpression()) {
                List<String> slotNames = parseReturnSlots();
                expect(COLON);
                MethodCall methodCall = parseMethodCall();
                methodCall.slotNames = slotNames;
                baseExpr = methodCall;
            } else {
                baseExpr = parseArrayLiteral();
            }
        }
        else if (isMethodCallFollows()) {
            MethodCall methodCall = parseMethodCall();
            baseExpr = methodCall;
        }
        else if (is(IF)) {
            baseExpr = parseIfExpr();
        }
        else if (is(INT_LIT)) {
            Token intToken = expect(INT_LIT);
            String intText = intToken.getText();
            try {
                int intValue = Integer.parseInt(intText);
                baseExpr = ASTFactory.createIntLiteral(intValue, intToken);
            } catch (NumberFormatException e1) {
                try {
                    long longValue = Long.parseLong(intText);
                    baseExpr = ASTFactory.createLongLiteral(longValue, intToken);
                } catch (NumberFormatException e2) {
                    AutoStackingNumber bigValue = AutoStackingNumber.valueOf(intText);
                    baseExpr = ASTFactory.createFloatLiteral(bigValue, intToken);
                }
            }
        }
        else if (is(FLOAT_LIT)) {
            Token floatToken = expect(FLOAT_LIT);
            String floatText = floatToken.getText();
            Object resolvedValue = resolveFloatLiteralValue(floatText);
            if (resolvedValue instanceof AutoStackingNumber) {
                baseExpr = ASTFactory.createFloatLiteral((AutoStackingNumber)resolvedValue, floatToken);
            } else {
                try {
                    AutoStackingNumber value = AutoStackingNumber.valueOf(floatText);
                    baseExpr = ASTFactory.createFloatLiteral(value, floatToken);
                } catch (NumberFormatException e) {
                    throw error("Invalid numeric literal: " + floatText, floatToken);
                }
            }
        }
        else if (is(TEXT_LIT, INTERPOL)) {
            Token textToken = now();
            if (textToken.type == INTERPOL && textToken.hasChildTokens()) {
                List<Expr> parts = new ArrayList<Expr>();
                for (Token part : textToken.childTokens) {
                    if (part.type == TEXT_LIT) {
                        parts.add(ASTFactory.createTextLiteral(part.getText(), part));
                    } else if (part.type == INTERPOL) {
                        if (part.hasChildTokens()) {
                            ParserContext subCtx = new ParserContext(part.childTokens);
                            ExpressionParser subParser = new ExpressionParser(subCtx, globalRegistry, statementParser);
                            Expr expr = subParser.parseExpr();
                            parts.add(expr);
                        }
                    }
                }
                if (parts.isEmpty()) {
                    baseExpr = ASTFactory.createTextLiteral("", textToken);
                } else if (parts.size() == 1) {
                    baseExpr = parts.get(0);
                } else {
                    baseExpr = parts.get(0);
                    for (int i = 1; i < parts.size(); i++) {
                        baseExpr = ASTFactory.createBinaryOp(baseExpr, "+", parts.get(i), textToken);
                    }
                }
            } else {
                String text = textToken.getText();
                if (text.startsWith("|\"") && text.endsWith("\"|")) {
                    baseExpr = handleMultilineTextInterpolation(textToken);
                } else {
                    baseExpr = ASTFactory.createTextLiteral(text, textToken);
                }
            }
            consume();
        }
        else if (is(TRUE)) {
            Token trueToken = expect(TRUE);
            baseExpr = ASTFactory.createBoolLiteral(true, trueToken);
        }
        else if (is(FALSE)) {
            Token falseToken = expect(FALSE);
            baseExpr = ASTFactory.createBoolLiteral(false, falseToken);
        }
        else if (is(NONE)) {
            Token noneToken = expect(NONE);
            baseExpr = ASTFactory.createNoneLiteral(noneToken);
        }
        else if (is(INT, TEXT, FLOAT, BOOL, TYPE, I8, I16, I32, I64, U8, U16, U32, U64, F32, F64)) {
            Token typeToken = now();
            String typeName = expect(KEYWORD).getText();
            baseExpr = ASTFactory.createTextLiteral(typeName, typeToken);
        }
        else if (is(ID) || canBeMethod(now())) {
            if (isMethodCallFollows()) {
                baseExpr = parseMethodCall();
            } else {
                Token idToken = now();
                String idName = is(KEYWORD) ? expect(KEYWORD).getText() : expect(ID).getText();
                baseExpr = ASTFactory.createIdentifier(idName, idToken);
            }
        }
        else if (is(DOLLAR)) {
            Token dollarToken = expect(DOLLAR);
            Token nameToken = expect(ID);
            baseExpr = ASTFactory.createIdentifier("$" + nameToken.getText(), dollarToken);
        }
        else if (is(LPAREN)) {
            if (isTypeCast()) {
                baseExpr = parseTypeCast();
            } else {
                Token lparenToken = expect(LPAREN);
                Expr firstExpr = parseExpr();
                if (is(COMMA)) {
                    List<Expr> elements = new ArrayList<Expr>();
                    elements.add(firstExpr);
                    while (consume(COMMA)) {
                        elements.add(parseExpr());
                    }
                    if (elements.size() == 1 && !is(RPAREN)) {
                        throw error("Expected expression after comma in tuple");
                    }
                    expect(RPAREN);
                    baseExpr = ASTFactory.createTuple(elements, lparenToken);
                } else {
                    expect(RPAREN);
                    baseExpr = firstExpr;
                }
            }
        }
        else {
            throw error("Unexpected token in primary expression: " + startToken.getText() + " (" + getTypeName(startToken.type) + ")", startToken);
        }

        while (is(DOT)) {
            Token dotToken = expect(DOT);
            Expr property = parsePrimaryExpr();
            baseExpr = ASTFactory.createPropertyAccess(baseExpr, property, dotToken);
        }

        while (is(LBRACKET)) {
            baseExpr = parseIndexAccessContinuation(baseExpr);
        }

        return baseExpr;
    }

    private Expr handleMultilineTextInterpolation(Token token) {
        String fullText = token.getText();
        if (!fullText.startsWith("|\"") || !fullText.endsWith("\"|")) {
            return ASTFactory.createTextLiteral(fullText, token);
        }
        if (token.hasChildTokens()) {
            return handleInterpolatedTextWithTokens(token);
        }
        return parseInterpolatedText(token);
    }

    private Expr handleInterpolatedTextWithTokens(Token token) {
        List<Token> exprTokens = token.childTokens;
        if (exprTokens == null || exprTokens.isEmpty()) {
            return ASTFactory.createTextLiteral(token.getText(), token);
        }
        List<Expr> parts = new ArrayList<Expr>();
        String fullText = token.getText();
        if (fullText.startsWith("\"") && fullText.endsWith("\"")) {
            fullText = fullText.substring(1, fullText.length() - 1);
        } else if (fullText.startsWith("|\"") && fullText.endsWith("\"|")) {
            fullText = fullText.substring(2, fullText.length() - 2);
        }
        int braceCount = 0;
        StringBuilder currentText = new StringBuilder();
        boolean inEscape = false;
        
        for (int i = 0; i < fullText.length(); i++) {
            char c = fullText.charAt(i);
            if (inEscape) {
                currentText.append('\\').append(c);
                inEscape = false;
            } else if (c == '\\') {
                inEscape = true;
            } else if (c == '{') {
                if (braceCount == 0) {
                    if (currentText.length() > 0) {
                        parts.add(ASTFactory.createTextLiteral(currentText.toString(), token));
                        currentText.setLength(0);
                    }
                    braceCount++;
                } else {
                    currentText.append(c);
                }
            } else if (c == '}') {
                if (braceCount == 1) {
                    if (!exprTokens.isEmpty()) {
                        Token exprToken = exprTokens.get(0);
                        if (exprToken.childTokens != null && !exprToken.childTokens.isEmpty()) {
                            Expr expr = parsePreTokenizedInterpolation(exprToken);
                            parts.add(expr);
                        } else {
                            parts.add(ASTFactory.createIdentifier(exprToken.getText(), exprToken));
                        }
                    } else {
                        parts.add(ASTFactory.createTextLiteral("", token));
                    }
                    braceCount = 0;
                } else {
                    currentText.append(c);
                }
            } else {
                currentText.append(c);
            }
        }
        if (currentText.length() > 0) {
            parts.add(ASTFactory.createTextLiteral(currentText.toString(), token));
        }
        if (parts.isEmpty()) return ASTFactory.createTextLiteral("", token);
        else if (parts.size() == 1) return parts.get(0);
        
        Expr result = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            result = ASTFactory.createBinaryOp(result, "+", parts.get(i), token);
        }
        return result;
    }

    private Expr parsePreTokenizedInterpolation(Token token) {
        List<Token> exprTokens = token.childTokens;
        if (exprTokens == null || exprTokens.isEmpty()) throw error("Interpolation token has no child tokens", token);
        
        ParserContext subCtx = new ParserContext(exprTokens);
        ExpressionParser subParser = new ExpressionParser(subCtx, globalRegistry, statementParser);
        Expr result = subParser.parseExpr();
        if (!subParser.ctx.atEOF()) throw error("Extra tokens in interpolation expression", token);
        return result;
    }

    private Expr parseInterpolatedText(Token token) {
        String text = token.getText();
        if (text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        } else if (text.startsWith("|\"") && text.endsWith("\"|")) {
            text = text.substring(2, text.length() - 2);
        }
        
        List<Expr> parts = new ArrayList<Expr>();
        StringBuilder current = new StringBuilder();
        int pos = 0;
        boolean inEscape = false;
        
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (inEscape) {
                switch (c) {
                    case 'n': current.append('\n'); break;
                    case 't': current.append('\t'); break;
                    case 'r': current.append('\r'); break;
                    case '\\': current.append('\\'); break;
                    case '"': current.append('"'); break;
                    case '{': current.append('{'); break;
                    case 'u':
                        UnicodeEscapeParseResult unicodeResult = decodeUnicodeEscape(text, pos, token);
                        current.append(unicodeResult.text);
                        pos = unicodeResult.nextPos;
                        inEscape = false;
                        continue;
                    default: current.append('\\').append(c); break;
                }
                inEscape = false;
                pos++;
                continue;
            }
            if (c == '\\') {
                inEscape = true;
                pos++;
            } else if (c == '{') {
                if (current.length() > 0) {
                    parts.add(ASTFactory.createTextLiteral(current.toString(), token));
                    current.setLength(0);
                }
                int end = text.indexOf('}', pos + 1);
                if (end == -1) throw error("Unclosed interpolation in text", token);
                String exprText = text.substring(pos + 1, end);
                Expr expr = parseInterpolationExpressionDirectly(exprText, token);
                parts.add(expr);
                pos = end + 1;
            } else {
                current.append(c);
                pos++;
            }
        }
        if (inEscape) current.append('\\');
        if (current.length() > 0) parts.add(ASTFactory.createTextLiteral(current.toString(), token));
        if (parts.isEmpty()) return ASTFactory.createTextLiteral("", token);
        else if (parts.size() == 1) return parts.get(0);
        
        Expr result = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            result = ASTFactory.createBinaryOp(result, "+", parts.get(i), null);
        }
        return result;
    }
    
    private int hexValue(char ch) {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
        if (ch >= 'A' && ch <= 'F') return ch - 'A' + 10;
        return -1;
    }
    
    private int parseUnicodeUnit(String text, int start, Token token) {
        if (start + 4 > text.length()) throw error("Incomplete Unicode escape in text literal", token);
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = hexValue(text.charAt(start + i));
            if (digit < 0) throw error("Invalid Unicode escape in text literal", token);
            value = (value << 4) + digit;
        }
        return value;
    }
    
    private UnicodeEscapeParseResult decodeUnicodeEscape(String text, int escapePos, Token token) {
        int firstUnit = parseUnicodeUnit(text, escapePos + 1, token);
        int nextPos = escapePos + 5;
        if (Character.isLowSurrogate((char)firstUnit)) throw error("Unexpected low surrogate in text Unicode escape", token);
        if (Character.isHighSurrogate((char)firstUnit)) {
            if (nextPos + 5 >= text.length()) throw error("Missing low surrogate in text Unicode escape", token);
            if (text.charAt(nextPos) != '\\' || text.charAt(nextPos + 1) != 'u') throw error("Expected low surrogate Unicode escape", token);
            int secondUnit = parseUnicodeUnit(text, nextPos + 2, token);
            if (!Character.isLowSurrogate((char)secondUnit)) throw error("Invalid low surrogate in text Unicode escape", token);
            return new UnicodeEscapeParseResult(new String(new char[] {(char)firstUnit, (char)secondUnit}), nextPos + 6);
        }
        return new UnicodeEscapeParseResult(String.valueOf((char)firstUnit), nextPos);
    }

    private Expr parseInterpolationExpressionDirectly(String exprText, Token textToken) {
        cod.lexer.MainLexer tempLexer = new cod.lexer.MainLexer(exprText, true);
        tempLexer.setLine(textToken.line);
        tempLexer.setColumn(textToken.column + 1);
        List<Token> tokens = tempLexer.tokenize();
        if (tokens.isEmpty()) return ASTFactory.createTextLiteral("", textToken);
        ParserContext subCtx = new ParserContext(tokens);
        ExpressionParser subParser = new ExpressionParser(subCtx, globalRegistry, statementParser);
        return subParser.parseExpr();
    }

    private boolean isThisKeyword() {
        Token current = now();
        if (is(current, THIS)) return true;
        if (is(current, ID) && is(next(), DOT) && is(next(2), THIS)) return true;
        return false;
    }

    private Expr parseThisExpr() {
        Token first = now();
        String className = null;
        if (is(first, ID) && is(next(), DOT) && is(next(2), THIS)) {
            Token classNameToken = expect(ID);
            className = classNameToken.getText();
            expect(DOT);
            Token thisToken = expect(THIS);
            return ASTFactory.createThisExpr(className, thisToken);
        }
        Token thisToken = expect(THIS);
        return ASTFactory.createThisExpr(null, thisToken);
    }

    private Object resolveFloatLiteralValue(String literal) {
        if (literal.contains(".")) return null;
        String baseValueStr;
        String suffix;
        int exponent = 0;
        
        if (literal.endsWith("Qi")) {
            suffix = "Qi";
            baseValueStr = literal.substring(0, literal.length() - 2);
            exponent = 18;
        } else {
            char lastChar = literal.charAt(literal.length() - 1);
            if (lastChar == 'K' || lastChar == 'M' || lastChar == 'B' || lastChar == 'T' || lastChar == 'Q') {
                suffix = String.valueOf(lastChar);
                baseValueStr = literal.substring(0, literal.length() - 1);
                if ("K".equals(suffix)) exponent = 3;
                else if ("M".equals(suffix)) exponent = 6;
                else if ("B".equals(suffix)) exponent = 9;
                else if ("T".equals(suffix)) exponent = 12;
                else if ("Q".equals(suffix)) exponent = 15;
            } else {
                return null;
            }
        }
        try {
            AutoStackingNumber base = AutoStackingNumber.valueOf(baseValueStr);
            AutoStackingNumber multiplier = AutoStackingNumber.fromLong(10).pow(exponent);
            return base.multiply(multiplier);
        } catch (Exception e) {
            return null;
        }
    }

    private Expr parsePrecedence(int precedence) {
        List<Expr> operands = new ArrayList<Expr>();
        List<Token> operators = new ArrayList<Token>();
        operands.add(parsePrefix());

        while (true) {
            Token op = now();
            if (op == null) break;

            int opPrecedence = getPrecedence(op);
            if (opPrecedence < precedence || !isReducibleInfixOperator(op)) break;

            if (isComparisonOp(op) && isChainComparison(1)) {
                Expr chainLeft = reduceFlatExpression(operands, operators);
                return parseComparisonChain(chainLeft);
            }

            if (isComparisonOp(op) && isChainFollows(1)) {
                Expr chainLeft = reduceFlatExpression(operands, operators);
                return parseEqualityChain(chainLeft, op.getText());
            }

            operators.add(consume());
            operands.add(parsePrefix());
        }
        return reduceFlatExpression(operands, operators);
    }

    private Expr parseComparisonChain(Expr first) {
        List<Expr> expressions = new ArrayList<Expr>();
        List<String> operators = new ArrayList<String>();
        Token firstToken = now();
        expressions.add(first);
        
        while (true) {
            Token opToken = now();
            if (opToken == null || !isComparisonOp(opToken)) break;
            
            operators.add(opToken.getText());
            consume();
            Expr nextExpr = parsePrecedence(PREC_COMPARISON + 1);
            expressions.add(nextExpr);
            
            Token nextToken = now();
            if (!isComparisonOp(nextToken)) break;
        }
        return ASTFactory.createChainedComparison(expressions, operators, firstToken);
    }

    private Expr reduceFlatExpression(List<Expr> operands, List<Token> operators) {
        if (operands.isEmpty()) throw error("Expected expression");
        if (operators.isEmpty()) return operands.get(0);

        for (int reductionPrecedence : REDUCTION_PRECEDENCE_ORDER) {
            reduceAtPrecedence(operands, operators, reductionPrecedence);
        }

        if (!operators.isEmpty()) throw error("Invalid expression near operator: " + operators.get(0).getText(), operators.get(0));
        if (operands.size() != 1) throw error("Invalid expression structure");
        return operands.get(0);
    }

    private void reduceAtPrecedence(List<Expr> operands, List<Token> operators, int precedence) {
        if (operators.isEmpty()) return;

        List<Expr> newOperands = new ArrayList<Expr>();
        List<Token> newOperators = new ArrayList<Token>();

        Expr current = operands.get(0);
        for (int i = 0; i < operators.size(); i++) {
            Token op = operators.get(i);
            Expr right = operands.get(i + 1);

            if (getPrecedence(op) == precedence) {
                if (is(op, IS)) {
                    current = ASTFactory.createBinaryOp(current, IS.toString(), right, op);
                } else {
                    current = ASTFactory.createBinaryOp(current, op.getText(), right, op);
                }
            } else {
                newOperands.add(current);
                newOperators.add(op);
                current = right;
            }
        }

        newOperands.add(current);
        operands.clear();
        operands.addAll(newOperands);
        operators.clear();
        operators.addAll(newOperators);
    }

    private boolean isReducibleInfixOperator(Token token) {
        if (token == null) return false;
        return is(token, IS) || is(token, PLUS, MINUS, MUL, DIV, MOD, EQ, NEQ, GT, LT, GTE, LTE);
    }

    private Expr parsePrefix() {
        Token current = now();
        if (current == null) throw error("Unexpected end of input in prefix expression");

        if (is(current, LT)) {
            Token markerToken = next(1);
            if (markerToken != null && is(markerToken, TILDE_ARROW)) {
                return parseSelfCall();
            }
        }
        
        if (is(BANG, PLUS, MINUS, AMPERSAND, MUL)) {
            Token opToken = consume();
            Expr operand = parsePrecedence(PREC_UNARY);
            return ASTFactory.createUnaryOp(opToken.getText(), operand, opToken);
        }
        return parsePrimaryExpr();
    }

    private MethodCall parseSelfCall() {
        Token ltToken = expect(LT);
        Token selfToken = expect(TILDE_ARROW);
        Integer selfCallLevel = null;
        String selfCallLevelConstantName = null;
        if (is(INT_LIT)) {
            Token levelToken = expect(INT_LIT);
            try {
                selfCallLevel = Integer.valueOf(levelToken.getText());
            } catch (NumberFormatException e) {
                throw error("Invalid self-call level after '<~': " + levelToken.getText(), levelToken);
            }
        } else if (is(ID)) {
            Token levelToken = expect(ID);
            String levelName = levelToken.getText();
            if (!NamingValidator.isAllCaps(levelName)) {
                throw error("Self-call level after '<~' must be an integer literal or ALL_CAPS constant name: " + levelName, levelToken);
            }
            selfCallLevelConstantName = levelName;
        }
        if (!is(LPAREN)) throw error("'<~' cannot be used without '()'. Use '<~(...)' for self-calls.", selfToken);

        MethodCall call = ASTFactory.createMethodCall(SELF_CALL_PLACEHOLDER, SELF_CALL_PLACEHOLDER, ltToken);
        call.isSelfCall = true;
        call.selfCallLevel = selfCallLevel;
        call.selfCallLevelConstantName = selfCallLevelConstantName;

        expect(LPAREN);
        if (!is(RPAREN)) {
            if (isNamedArgument()) {
                parseNamedArgumentList(call.arguments, call.argNames);
            } else {
                call.arguments.add(parseExpr());
                call.argNames.add(null);
                while (consume(COMMA)) {
                    call.arguments.add(parseExpr());
                    call.argNames.add(null);
                }
            }
        }
        expect(RPAREN);
        if (is(now(), PLUS, MINUS, MUL, DIV, MOD, EQ, NEQ, GT, LT, GTE, LTE)) {
            throw error("Invalid trailing operation after '<~(...)'. Wrap the complete expression.", now());
        }
        return call;
    }

    private Expr parseBooleanChain() {
        Token typeToken = now();
        boolean isAll = is(typeToken, ALL);
        consume();

        if (is(ID)) {
            Token arrayNameToken = now();
            String arrayName = expect(ID).getText();

            if (isComparisonOp(now())) {
                Token opToken = consume();
                Expr right = parsePrecedence(PREC_COMPARISON + 1);                

                List<Expr> chainArgs = new ArrayList<Expr>();
                chainArgs.add(right);

                Expr arrayExpr = ASTFactory.createIdentifier(arrayName, arrayNameToken);
                return ASTFactory.createEqualityChain(arrayExpr, opToken.getText(), isAll, chainArgs, arrayNameToken, opToken, typeToken);
            } else {
                throw error("Expected comparison operator after 'all/any <arrayName>'");
            }
        } else if (is(LBRACKET)) {
            expect(LBRACKET);
            List<Expr> expressions = new ArrayList<Expr>();

            if (!is(RBRACKET)) {
                expressions.add(parseExpr());
                if (!is(COMMA, RBRACKET)) throw error("Boolean chain requires at least two expressions or a comma after the first expression.");                 
                while (consume(COMMA)) {
                    expressions.add(parseExpr());
                }
            }
            expect(RBRACKET);
            return ASTFactory.createBooleanChain(isAll, expressions, typeToken);
        } else {
            throw error("Expected array variable or '[' after 'all/any'");
        }
    }

    private Expr parseEqualityChain(Expr left, String operator) {
        Token leftToken = next(-1);
        Token operatorToken = now();
        consume();
        
        Token chainTypeToken = now();
        boolean isAllChain = is(chainTypeToken, ALL);
        
        if (!is(chainTypeToken, ALL, ANY)) throw error("Expected 'all' or 'any' after comparison operator", chainTypeToken);
        consume();
        
        List<Expr> chainArgs = new ArrayList<Expr>();
        if (is(LBRACKET)) {
            expect(LBRACKET);
            if (!is(RBRACKET)) {
                chainArgs.add(parseChainArgument());
                while (consume(COMMA)) chainArgs.add(parseChainArgument());
            }
            expect(RBRACKET);
        } else if (is(ID)) {
            Token arrayNameToken = now();
            String arrayName = expect(ID).getText();
            chainArgs.add(ASTFactory.createIdentifier(arrayName, arrayNameToken));
        } else {
            throw error("Expected array variable or '[' for array literal after 'all/any'");
        }
        return ASTFactory.createEqualityChain(left, operator, isAllChain, chainArgs, leftToken, operatorToken, chainTypeToken);
    }

    private Expr parseChainArgument() {
        if (is(BANG)) {
            Token bangToken = expect(BANG);
            Expr arg = parsePrimaryExpr();
            return ASTFactory.createUnaryOp("!", arg, bangToken);
        }
        if (is(LPAREN)) return parseArgumentList();
        if (is(ID) && is(next(), RBRACKET)) {
            Token idToken = now();
            throw error("Redundant brackets around array variable '" + idToken.getText() + "'.", idToken);
        }
        return parsePrimaryExpr();
    }

    private Expr parseArgumentList() {
        Token lparenToken = expect(LPAREN);
        List<Expr> arguments = new ArrayList<Expr>();
        if (!is(RPAREN)) {
            arguments.add(parseExpr());
            while (consume(COMMA)) arguments.add(parseExpr());
        }
        expect(RPAREN);
        return ASTFactory.createArgumentList(arguments, lparenToken);
    }

    private Expr parseArrayLiteral() {
        Token lbracketToken = expect(LBRACKET);
        List<Expr> elements = new ArrayList<Expr>();
        
        if (!is(RBRACKET)) {
            Expr first = parseExpr();
            if (is(RANGE_DOTDOT) || is(TO)) {
                elements.add(finishRangeExpression(first));
            } else {
                elements.add(first);
            }
            while (consume(COMMA)) {
                Expr nextExpr = parseExpr();
                if (is(RANGE_DOTDOT) || is(TO)) {
                    elements.add(finishRangeExpression(nextExpr));
                } else {
                    elements.add(nextExpr);
                }
            }
        }
        expect(RBRACKET);
        return ASTFactory.createArray(elements, lbracketToken);
    }

    private Range finishRangeExpression(Expr start) {
        Token rangeToken = consume();
        Expr end = parseExpr();
        Expr step = null;
        Token stepToken = null;
        if (is(BY) || is(RANGE_HASH)) {
            stepToken = consume();
            step = parseExpr();
        }
        return ASTFactory.createRange(step, start, end, stepToken, rangeToken);
    }

    private Expr parseTypeCast() {
        Token lparenToken = expect(LPAREN);
        String type = parseTypeReference();    
        expect(RPAREN);
        Expr expressionToCast = parsePrecedence(PREC_UNARY);
        return ASTFactory.createTypeCast(type, expressionToCast, lparenToken);
    }

    private boolean isConstructorCall() {
        Token first = now();
        if (!is(first, ID)) return false;
        String idName = first.getText();
        if (idName.length() == 0 || Character.isLowerCase(idName.charAt(0))) return false;
        return is(next(), LPAREN);
    }

    private boolean isMethodCallFollows() {
        Token first = now();
        if (!(is(first, ID) || canBeMethod(first))) return false;
        if (isConstructorCall()) return false;
        
        int pos = 1;
        while (is(next(pos), DOT)) {
            pos++;
            Token afterDot = next(pos);
            if (!(is(afterDot, ID) || canBeMethod(afterDot))) return false;
            pos++;
        }
        return is(next(pos), LPAREN);
    }

    private boolean isSlotAccessExpression() {
        if (!is(now(), LBRACKET)) return false;
        int pos = getPosition() + 1;
        int depth = 1;
        while (pos < tokens.size() && depth > 0) {
            Token t = tokens.get(pos);
            if (is(t, LBRACKET)) depth++;
            else if (is(t, RBRACKET)) depth--;
            pos++;
        }
        if (depth == 0 && pos < tokens.size()) return is(tokens.get(pos), COLON);
        return false;
    }

    private boolean isTypeCast() {
        if (!is(next(0), LPAREN)) return false;
        Token second = next();
        if (!isTypeStart(second)) return false;
        
        int pos = getPosition();
        int parenDepth = 0;
        
        for (int i = 0; i < 50 && pos < tokens.size(); i++) {
            Token t = tokens.get(pos);
            if (parenDepth > 0 && isIllegalTypeToken(t)) return false;
            if (is(t, LPAREN)) parenDepth++;
            else if (is(t, RPAREN)) {
                parenDepth--;
                if (parenDepth == 0) {
                    if (pos + 1 < tokens.size()) {
                        Token afterParen = tokens.get(pos + 1);
                        return isExprStart(afterParen);
                    }
                    return false;
                }
            }
            pos++;
        }
        return false;
    }

    private boolean isIllegalTypeToken(Token t) {
        if (is(t, INT_LIT, FLOAT_LIT, TEXT_LIT, BOOL_LIT)) return true;
        if (is(t, PLUS, MINUS, MUL, DIV, MOD, EQ, NEQ, GT, LT, GTE, LTE)) return true;
        return false;
    }

    private int getPrecedence(Token token) {
        if (nil(token)) return 0;
        if (is(token, IS)) return PREC_IS;
        if (is(token, SYMBOL)) {
            switch (token.symbol) {
                case EQ: case NEQ: 
                    if (isChainComparison(1)) return PREC_CHAIN;
                    return PREC_EQUALITY;
                case LT: case GT: case LTE: case GTE: 
                    if (isChainComparison(1)) return PREC_CHAIN;
                    return PREC_COMPARISON;
                case PLUS: case MINUS: return PREC_TERM;
                case MUL: case DIV: case MOD: return PREC_FACTOR;
                case LPAREN: case LBRACKET: return PREC_CALL;
                default: return 0;
            }
        }
        return 0;
    }

    private boolean isChainComparison(int offset) {
        Token currentOp = now();
        if (!isComparisonOp(currentOp)) return false;
        
        int posOffset = offset;
        if (!isExprStart(next(posOffset))) return false;
        
        while (true) {
            Token t = next(posOffset);
            if (t == null || t.type == EOF) break;
            if (is(t, RPAREN, RBRACE, RBRACKET, COMMA)) break;
            if (isComparisonOp(t) && t != currentOp) return true;
            posOffset++;
        }
        return false;
    }

    private boolean isChainFollows(int offset) {
        Token next = next(offset);
        if (next == null) return false;
        if (is(next, ALL, ANY)) {
            Token after = next(offset + 1);
            return is(after, LBRACKET) || is(after, ID);
        }
        return false;
    }

    private boolean isComparisonOp(Token t) {
        if (t == null) return false;
        return is(t, EQ, NEQ, GT, LT, GTE, LTE);
    }
    
    public GlobalRegistry getGlobalRegistry() {
        return globalRegistry;
    }
}