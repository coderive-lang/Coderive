package cod.parser;

import cod.ast.ASTFactory;
import cod.ast.node.*;
import cod.error.ParseError;
import cod.interpreter.Interpreter;
import cod.interpreter.registry.GlobalRegistry;
import cod.semantic.ModuleValidator;
import java.util.ArrayList;
import java.util.List;
import cod.lexer.Token;
import static cod.lexer.TokenType.*;
import cod.parser.context.*;
import static cod.lexer.TokenType.Keyword.*;
import static cod.lexer.TokenType.Symbol.*;

public class MainParser extends BaseParser {
    private static final String DEFAULT_UNIT_NAME = "default";
    private static final String SELF_BROADCAST_NAME = "this";

    private final ExpressionParser expressionParser;
    private final StatementParser statementParser;
    private final DeclarationParser declarationParser;
    private final Interpreter interpreter;
    
    public enum ProgramType {
        SCRIPT,
        STATIC_MODULE,
        MODULE
    }

    public MainParser(List<Token> tokens) {
        this(tokens, null);
    }
    
    public MainParser(List<Token> tokens, Interpreter interpreter) {
        super(new ParserContext(new ParserState(tokens)));
        this.interpreter = interpreter;
        
        GlobalRegistry globalRegistry = interpreter != null ? interpreter.getGlobalRegistry() : null;
        
        this.expressionParser = new ExpressionParser(ctx, globalRegistry, null);
        this.statementParser = new StatementParser(ctx, expressionParser);
        this.expressionParser.setStatementParser(this.statementParser);
        this.declarationParser = new DeclarationParser(ctx, statementParser, interpreter != null ? interpreter.getImportResolver() : null);
    }
    
    @Override
    protected BaseParser createIsolatedParser(ParserContext isolatedCtx) {
        return new MainParser(isolatedCtx.getState().getTokens(), interpreter);
    }
    
    public Program parseProgram() {
        Program program = ASTFactory.createProgram();
        
        if (is(UNIT)) {
            program.unit = parseUnit();
        } else {
            program.unit = ASTFactory.createUnit(DEFAULT_UNIT_NAME, (Token) null);
        }

        while (is(USE)) {
            if (program.unit.imports == null) {
                program.unit.imports = parseUseNode();
            } else {
                Use additionalImports = parseUseNode();
                program.unit.imports.imports.addAll(additionalImports.imports);
            }
        }

        List<Type> typesInFile = new ArrayList<Type>();
        List<Policy> policiesInFile = new ArrayList<Policy>();
        List<Stmt> topLevelStatements = new ArrayList<Stmt>();
        List<Method> topLevelMethods = new ArrayList<Method>();
        List<Field> topLevelFields = new ArrayList<Field>();
        
        while (!is(EOF)) {
            if (isTopLevelMethodDeclaration()) {
                topLevelMethods.add(declarationParser.parseMethod());
                continue;
            }
            
            if (isTopLevelFieldDeclaration()) {
                topLevelFields.add(declarationParser.parseField());
                continue;
            }
            
            if (declarationParser.isPolicyDeclaration()) {
                Policy policy = declarationParser.parsePolicy();
                program.unit.policies.add(policy);
                policiesInFile.add(policy);
                continue;
            }
            
            if (isClassStart() || isClassStartWithoutModifier()) {
                Type type = declarationParser.parseType();
                if (type != null) {
                    program.unit.types.add(type);
                    typesInFile.add(type);
                    continue;
                }
            }
            
            // LL(k) Fallback: if it's not a valid top level declaration, it must be a script statement
            topLevelStatements.add(statementParser.parseStmt());
        }
        
        program.programType = ModuleValidator.determineProgramType(
            program, topLevelStatements, topLevelMethods, typesInFile, policiesInFile, now()
        );
        
        if (program.programType == ProgramType.STATIC_MODULE) {
            Type staticModuleType = findOrCreateImplicitType(program.unit, "__StaticModule__");
            staticModuleType.methods.addAll(topLevelMethods);
            staticModuleType.fields.addAll(topLevelFields);
        } else if (program.programType == ProgramType.SCRIPT) {
            Type scriptType = findOrCreateImplicitType(program.unit, "__Script__");
            scriptType.statements.addAll(topLevelStatements);
            
            if (topLevelStatements != null && !topLevelStatements.isEmpty()) {
                Method syntheticMain = ASTFactory.createMethod("main", Keyword.SHARE, null, null);
                syntheticMain.body = new ArrayList<Stmt>();
                syntheticMain.body.addAll(topLevelStatements);
                scriptType.methods.add(syntheticMain);
            }
        }
        
        if (program.programType == ProgramType.STATIC_MODULE || program.programType == ProgramType.MODULE) {
            ModuleValidator.validateModule(program, typesInFile, policiesInFile, interpreter, declarationParser, now());
        }
        
        return program;
    }

    private boolean isTopLevelMethodDeclaration() {
        if (match(BUILTIN)) return true;
        if (match(MODIFIERS, BUILTIN)) return true;
        if (match(MODIFIERS, MODIFIERS, BUILTIN)) return true;

        // Check if we have Name + (
        int offset = 0;
        if (is(next(offset), SHARE, LOCAL, UNSAFE)) offset++;
        if (!(is(next(offset), ID) || canBeMethod(next(offset)))) return false;
        if (!is(next(offset + 1), LPAREN)) return false;

        // Disambiguate Script Method Call vs Declaration: Scan past the parentheses
        int scan = offset + 2;
        int depth = 1;
        while (depth > 0 && scan < tokens.size()) {
            Token t = next(scan++);
            if (t == null || t.type == EOF) break;
            if (is(t, LPAREN)) depth++;
            else if (is(t, RPAREN)) depth--;
        }

        // LL(k) Decision: If it's a declaration, it MUST have a contract or body marker
        Token afterParen = next(scan);
        return is(afterParen, DOUBLE_COLON, TILDE_ARROW, LBRACE);
    }

    private boolean isTopLevelFieldDeclaration() {
        if (match(ID, COLON)) return true;
        if (match(VISIBILITY, ID, COLON)) return true;
        return false;
    }

    private Type findOrCreateImplicitType(Unit unit, String typeName) {
        for (Type type : unit.types) {
            if (type.name.equals(typeName)) return type;
        }
        Type implicitType = ASTFactory.createType(typeName, SHARE, null, null);
        unit.types.add(implicitType);
        return implicitType;
    }

    private Unit parseUnit() {
        Token unitToken = expect(UNIT);
        String unitName = parseQualifiedName();
        
        String mainClassName = null;
        if (match(LPAREN, ID, COLON)) {
            consume(); // LPAREN
            if ("main".equals(now().getText())) {
                consume(); // ID(main)
                consume(); // COLON
                Token mainTargetToken = now();
                if (is(mainTargetToken, THIS) || (is(mainTargetToken, ID) && SELF_BROADCAST_NAME.equals(mainTargetToken.getText()))) {
                    mainClassName = consume().getText();
                } else {
                    mainClassName = parseQualifiedName();
                }
                expect(RPAREN);
            }
        }
        
        Unit unit = ASTFactory.createUnit(unitName, unitToken);
        unit.mainClassName = mainClassName;
        return unit;
    }

    private Use parseUseNode() {
        Token useToken = expect(USE);
        expect(LBRACE);
        List<String> imports = new ArrayList<String>();
        if (!is(RBRACE)) {
            imports.add(parseUseImportSpec());
            while (consume(COMMA)) {
                imports.add(parseUseImportSpec());
            }
        }
        expect(RBRACE);
        return ASTFactory.createUseNode(imports, useToken);
    }

    private String parseUseImportSpec() {
        StringBuilder spec = new StringBuilder();
        int parenDepth = 0;
        int bracketDepth = 0;
        
        while (!is(EOF)) {
            if (parenDepth == 0 && bracketDepth == 0 && (is(COMMA) || is(RBRACE))) {
                break;
            }
            Token token = consume();
            if (token == null || token.type == EOF) break;
            
            if (token.symbol == LPAREN) parenDepth++;
            else if (token.symbol == RPAREN && parenDepth > 0) parenDepth--;
            else if (token.symbol == LBRACKET) bracketDepth++;
            else if (token.symbol == RBRACKET && bracketDepth > 0) bracketDepth--;
            
            spec.append(token.getText());
        }
        
        String value = spec.toString();
        if (value.isEmpty()) {
            throw error("Expected import spec inside use block");
        }
        return value;
    }

    public Stmt parseSingleLine() {
        if (is(EOF)) return null;
        Stmt stmt = statementParser.parseStmt();
        if (!is(EOF)) {
            Token current = now();
            throw error("Unexpected token after statement: " + getTypeName(current.type) + " ('" + current.getText() + "')", current);
        }
        return stmt;
    }
}