package cod.ast;

import static cod.lexer.TokenType.Keyword.*;
import cod.ast.node.*;

public class ASTPrinter extends ASTVisitor<Void> {
    private int indent = 0;
    
    private String getIndent() {
        return new String(new char[indent]).replace("\0", "|   ");
    }
    
    private void print(String message) {
        System.out.print(getIndent() + message);
    }
    
    private void println(String message) {
        System.out.print(getIndent() + message + "\n");
    }
    
    @Override
    public Void visit(Program node) {
        println("\nPROGRAM");
        println("|   ");
        if (node.unit != null) visit(node.unit);
        return null;
    }
    
    @Override
    public Void visit(Unit node) {
        println("|   UNIT: " + node.name);
        
        if (node.imports != null) visit(node.imports);
        visitAll(node.types);
        println("|   ");
        return null;
    }
    
    @Override
    public Void visit(Use node) {
            println("|   USE imports: " + (node.imports.isEmpty() ? "[]" : node.imports));
        return null;
    }
    
    @Override
    public Void visit(Type node) {
        println("|   CLASS TYPE: " + node.name + " extends: " + node.extendName + " visibility: " + node.visibility + "\n|   |   ");
        indent++;
        visitAll(node.fields);
        if (node.constructor != null) visit(node.constructor);
        visitAll(node.methods);
        visitAll(node.statements);
        indent--;
        return null;
    }
    
    @Override
    public Void visit(Field node) {
        println("FIELD: " + node.type + " " + node.name + " visibility: " + node.visibility);
        if (node.value != null) {
            println("|   value:");
            indent += 2;
            visit(node.value);
            indent -= 2;
        }
        return null;
    }
    
    @Override
    public Void visit(Method node) {
        print("|   METHOD: " + node.methodName + " slots: ");
        for (Slot s : node.returnSlots) System.out.print(s.name + " ");
        System.out.println(" visibility: " + node.visibility);
        visitAll(node.parameters);
        visitAll(node.body);
        return null;
    }
    
    @Override
    public Void visit(Param node) {
        println("|   PARAM: " + node.type + " " + node.name);
        return null;
    }
    
    @Override
    public Void visit(Constructor node) {
        println("CONSTRUCTOR PARAMS: " + node.parameters.size());
        indent++;
        visitAll(node.parameters);
        visitAll(node.body);
        indent--;
        return null;
    }
    
    @Override
    public Void visit(Block node) {
        println("BLOCK:");
        indent++;
        visitAll(node.statements);
        indent--;
        return null;
    }
    
    @Override
    public Void visit(Assignment node) {
        println("ASSIGNMENT:");
        println("|   target:");
        indent += 2;
        if (node.left != null) visit(node.left);
        indent -= 2;
        println("|   value:");
        indent += 2;
        if (node.right != null) visit(node.right);
        indent -= 2;
        return null;
    }
    
    @Override
    public Void visit(Var node) {
        print("VAR: " + node.name);
        if (node.explicitType != null) {
            System.out.print(" (type: " + node.explicitType + ")");
        }
        if (node.value != null) {
            System.out.println(" =");
            indent++;
            visit(node.value);
            indent--;
        } else {
            System.out.println();
        }
        return null;
    }
    
    @Override
    public Void visit(StmtIf node) {
        println("|   |   IF condition:");
        indent ++;
        if (node.condition != null) visit(node.condition);
        indent --;
        println("|   |   |   THEN execute:");
        indent += 2;
        if (node.thenBlock != null) visit(node.thenBlock);
        indent -= 2;
        if (node.elseBlock != null && !node.elseBlock.statements.isEmpty()) {
            println("|   |   ELSE:");
            indent += 2;
            visit(node.elseBlock);
            indent -= 2;
        }
        return null;
    }
    
    @Override
    public Void visit(For node) {
        println("FOR iterator: " + node.iterator);
        indent += 2;
        if (node.range != null) visit(node.range);
        indent -= 2;
        println("|   BODY:");
        indent += 2;
        if (node.body != null) visit(node.body);
        indent -= 2;
        return null;
    }
    
    @Override
    public Void visit(Range node) {
        println("|   step:");
        indent += 2;
        if (node.step != null) visit(node.step);
        indent -= 2;
        println("RANGE:");
        println("|   start:");
        indent += 2;
        if (node.start != null) visit(node.start);
        indent -= 2;
        println("|   end:");
        indent += 2;
        if (node.end != null) visit(node.end);
        indent -= 2;
        return null;
    }
    
    @Override
    public Void visit(Exit node) {
        println("EXIT");
        return null;
    }
    
    @Override
    public Void visit(ReturnSlotAssignment node) {
        println("RETURN SLOT ASSIGNMENT:");
        println("|   VARIABLES: " + node.variableNames);
        println("|   METHOD CALL:");
        indent += 2;
        if (node.methodCall != null) visit(node.methodCall);
        indent -= 2;
        return null;
    }
    
    @Override
    public Void visit(SlotDeclaration node) {
        println("SLOT DECLARATION: " + node.slotNames);
        return null;
    }
    
    @Override
    public Void visit(SlotAssignment node) {
        println("SLOT ASSIGNMENT:");
        println("|   slot: " + (node.slotName != null ? node.slotName : "(implicit)"));
        println("|   value:");
        indent += 2;
        if (node.value != null) visit(node.value);
        indent -= 2;
        return null;
    }
    
    @Override
    public Void visit(MultipleSlotAssignment node) {
        println("MULTIPLE SLOT ASSIGNMENT:");
        indent++;
        for (int i = 0; i < node.assignments.size(); i++) {
            SlotAssignment assign = node.assignments.get(i);
            println("ASSIGNMENT " + i + ":");
            println("|   slot: " + (assign.slotName != null ? assign.slotName : "(positional)"));
            println("|   value:");
            indent += 2;
            if (assign.value != null) visit(assign.value);
            indent -= 2;
        }
        indent--;
        return null;
    }
    
    @Override
    public Void visit(Expr node) {
        // This should never be called directly now since Expr is abstract
        println("EXPR (abstract)");
        return null;
    }
    
    // === New visit methods for specific expression types ===
    
    @Override
    public Void visit(Identifier node) {
        println("IDENTIFIER: " + node.name);
        return null;
    }
    
    @Override
    public Void visit(IntLiteral node) {
        println("INT LITERAL: " + node.value);
        return null;
    }
    
    @Override
    public Void visit(FloatLiteral node) {
        println("FLOAT LITERAL: " + node.value.toString());
        return null;
    }
    
    @Override
    public Void visit(TextLiteral node) {
        if (node.isInterpolated) {
            println("INTERPOLATED TEXT: \"" + node.value + "\"");
        } else {
            println("TEXT LITERAL: \"" + node.value + "\"");
        }
        return null;
    }
    
    @Override
    public Void visit(BoolLiteral node) {
        println("BOOL LITERAL: " + node.value);
        return null;
    }
    
    @Override
    public Void visit(NoneLiteral node) {
        println("NONE LITERAL");
        return null;
    }
    
    @Override
    public Void visit(This node) {
        if (node.className != null) {
            println("THIS: " + node.className + ".this");
        } else {
            println("THIS");
        }
        return null;
    }
    
    @Override
    public Void visit(Super node) {
        println("SUPER");
        return null;
    }
    
    @Override
    public Void visit(BinaryOp node) {
        println("|   BINARY operation: " + node.op);
        println("|   left:");
        indent += 2;
        if (node.left != null) visit(node.left);
        indent -= 2;
        println("|   right:");
        indent += 2;
        if (node.right != null) visit(node.right);
        indent -= 2;
        return null;
    }
    
    @Override
    public Void visit(Unary node) {
        println("UNARY: " + node.op);
        indent++;
        if (node.operand != null) visit(node.operand);
        indent--;
        return null;
    }
    
    @Override
    public Void visit(TypeCast node) {
        println("TYPECAST: " + node.targetType);
        println("|   expression:");
        indent += 2;
        if (node.expression != null) visit(node.expression);
        indent -= 2;
        return null;
    }
    
    @Override
    public Void visit(MethodCall node) {
        print("IDENTIFIER/CALL: " + (node.qualifiedName != null ? node.qualifiedName : node.name));
        
        // Display slot names if present
        if (node.slotNames != null && !node.slotNames.isEmpty()) {
            System.out.print(" (slot_cast: ");
            for (int i = 0; i < node.slotNames.size(); i++) {
                if (i > 0) System.out.print(", ");
                System.out.print(node.slotNames.get(i));
            }
            System.out.print(")");
        }
        
        System.out.println();
        
        // Display arguments
        if (node.arguments != null && !node.arguments.isEmpty()) {
            println("|   ARGUMENTS:");
            indent += 2;
            for (Expr arg : node.arguments) {
                visit(arg);
            }
            indent -= 2;
        } else {
            println("|   (no arguments)");
        }
        
        return null;
    }
    
    @Override
    public Void visit(Array node) {
        println("ARRAY literal with " + node.elements.size() + " elements:");
        indent++;
        for (int i = 0; i < node.elements.size(); i++) {
            println("[" + i + "]:");
            indent++;
            visit(node.elements.get(i));
            indent--;
        }
        indent--;
        return null;
    }
    
    @Override
    public Void visit(IndexAccess node) {
        println("INDEX access");
        println("|   ARRAY:");
        indent += 2;
        if (node.array != null) visit(node.array);
        indent -= 2;
        println("|   INDEX:");
        indent += 2;
        if (node.index != null) visit(node.index);
        indent -= 2;
        return null;
    }
    
    @Override
    public Void visit(RangeIndex node) {
        println("RANGE INDEX");
        if (node.step != null) {
            println("|   step:");
            indent += 2;
            visit(node.step);
            indent -= 2;
        }
        println("|   start:");
        indent += 2;
        if (node.start != null) visit(node.start);
        indent -= 2;
        println("|   end:");
        indent += 2;
        if (node.end != null) visit(node.end);
        indent -= 2;
        return null;
    }
    
    @Override
    public Void visit(MultiRangeIndex node) {
        println("MULTI-RANGE INDEX with " + node.ranges.size() + " ranges:");
        indent++;
        for (int i = 0; i < node.ranges.size(); i++) {
            println("RANGE " + i + ":");
            indent++;
            visit(node.ranges.get(i));
            indent--;
        }
        indent--;
        return null;
    }
    
    @Override
    public Void visit(EqualityChain node) {
        println("EQUALITY chain: " + (node.isAllChain ? ALL : ANY) + " " + node.operator);
        println("|   LEFT:");
        indent += 2;
        if (node.left != null) visit(node.left);
        indent -= 2;
        println("|   CHAIN arguments:");
        indent += 2;
        for (Expr arg : node.chainArguments) {
            visit(arg);
        }
        indent -= 2;
        return null;
    }
    
    @Override
    public Void visit(BooleanChain node) {
        println("BOOLEAN chain: " + (node.isAll ? ALL : ANY));
        indent++;
        for (Expr expr : node.expressions) {
            visit(expr);
        }
        indent--;
        return null;
    }
    
    @Override
    public Void visit(Slot node) {
        println("SLOT: " + node.name + " (type: " + node.type + ")");
        return null;
    }
    
    @Override
    public Void visit(Lambda node) {
        print("LAMBDA with parameters: ");
        if (node.parameters != null && !node.parameters.isEmpty()) {
            for (int i = 0; i < node.parameters.size(); i++) {
                if (i > 0) System.out.print(", ");
                System.out.print(node.parameters.get(i).name);
            }
        }
        System.out.println();
        return null;
    }
    
    @Override
    public Void visit(PropertyAccess node) {
        println("PROPERTY ACCESS (left HAS right)");
        println("|   left:");
        indent += 2;
        if (node.left != null) visit(node.left);
        indent -= 2;
        println("|   right:");
        indent += 2;
        if (node.right != null) visit(node.right);
        indent -= 2;
        return null;
    }
    
@Override
public Void visit(ChainedComparison node) {
    println("CHAINED COMPARISON:");
    indent++;
    for (int i = 0; i < node.expressions.size(); i++) {
        if (i > 0) {
            println("OPERATOR: " + node.operators.get(i-1));
        }
        println("EXPRESSION " + i + ":");
        indent++;
        visit(node.expressions.get(i));
        indent--;
    }
    indent--;
    return null;
}
    
    public static void print(Base node) {
        ASTPrinter printer = new ASTPrinter();
        printer.visit(node);
    }
}