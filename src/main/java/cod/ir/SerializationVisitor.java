package cod.ir;

import cod.ast.VisitorImpl;
import cod.ast.node.*;

import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class SerializationVisitor implements VisitorImpl<Void> {
    private final DataOutput out;
    private final int depth;

    SerializationVisitor(DataOutput out, int depth) {
        this.out = out;
        this.depth = depth;
    }

    Void write(Base node) {
        return node.accept(this);
    }

    private void writeNodeStart(String nodeName, int fieldCount) {
        try {
            IRCodec.writeNodeStart(out, nodeName, fieldCount);
        } catch (IOException e) {
            throw new SerializationException(e);
        }
    }

    private void writeNodeField(String fieldName, Object value) {
        try {
            IRCodec.writeNodeField(out, fieldName, value, depth);
        } catch (IOException e) {
            throw new SerializationException(e);
        }
    }

    static final class SerializationException extends RuntimeException {
        public final static long serialVersionUID = -1;
        final IOException io;

        SerializationException(IOException io) {
            this.io = io;
        }
    }

    @Override
    public Void visit(Program n) {
        writeNodeStart("Program", 2);
        writeNodeField("unit", n.unit);
        writeNodeField("programType", n.programType);
        return null;
    }

    @Override
    public Void visit(Unit n) {
        writeNodeStart("Unit", 6);
        writeNodeField("name", n.name);
        writeNodeField("imports", n.imports);
        writeNodeField("policies", n.policies);
        writeNodeField("types", n.types);
        writeNodeField("mainClassName", n.mainClassName);
        writeNodeField("resolvedImports", n.resolvedImports);
        return null;
    }

    @Override
    public Void visit(Use n) {
        writeNodeStart("Use", 1);
        writeNodeField("imports", n.imports);
        return null;
    }

    @Override
    public Void visit(Type n) {
        writeNodeStart("Type", 11);
        writeNodeField("name", n.name);
        writeNodeField("visibility", n.visibility);
        writeNodeField("extendName", n.extendName);
        writeNodeField("fields", n.fields);
        writeNodeField("constructor", n.constructor);
        writeNodeField("methods", n.methods);
        writeNodeField("statements", n.statements);
        writeNodeField("constructors", n.constructors);
        writeNodeField("implementedPolicies", n.implementedPolicies);
        writeNodeField("cachedAncestorPolicies", n.cachedAncestorPolicies);
        writeNodeField("viralPoliciesValidated", Boolean.valueOf(n.viralPoliciesValidated));
        return null;
    }

    @Override
    public Void visit(Field n) {
        writeNodeStart("Field", 4);
        writeNodeField("name", n.name);
        writeNodeField("type", n.type);
        writeNodeField("visibility", n.visibility);
        writeNodeField("value", n.value);
        return null;
    }

    @Override
    public Void visit(Method n) {
        writeNodeStart("Method", 8);
        writeNodeField("methodName", n.methodName);
        writeNodeField("associatedClass", n.associatedClass);
        writeNodeField("visibility", n.visibility);
        writeNodeField("returnSlots", n.returnSlots);
        writeNodeField("parameters", n.parameters);
        writeNodeField("body", n.body);
        writeNodeField("isBuiltin", Boolean.valueOf(n.isBuiltin));
        writeNodeField("isPolicyMethod", Boolean.valueOf(n.isPolicyMethod));
        return null;
    }

    @Override
    public Void visit(Param n) {
        writeNodeStart("Param", 8);
        writeNodeField("name", n.name);
        writeNodeField("type", n.type);
        writeNodeField("defaultValue", n.defaultValue);
        writeNodeField("hasDefaultValue", Boolean.valueOf(n.hasDefaultValue));
        writeNodeField("typeInferred", Boolean.valueOf(n.typeInferred));
        writeNodeField("isLambdaParameter", Boolean.valueOf(n.isLambdaParameter));
        writeNodeField("isTupleDestructuring", Boolean.valueOf(n.isTupleDestructuring));
        writeNodeField("tupleElements", n.tupleElements);
        return null;
    }

    @Override
    public Void visit(Constructor n) {
        writeNodeStart("Constructor", 2);
        writeNodeField("parameters", n.parameters);
        writeNodeField("body", n.body);
        return null;
    }

    @Override
    public Void visit(ConstructorCall n) {
        writeNodeStart("ConstructorCall", 3);
        writeNodeField("className", n.className);
        writeNodeField("arguments", n.arguments);
        writeNodeField("argNames", n.argNames);
        return null;
    }

    @Override
    public Void visit(Policy n) {
        writeNodeStart("Policy", 5);
        writeNodeField("name", n.name);
        writeNodeField("visibility", n.visibility);
        writeNodeField("methods", n.methods);
        writeNodeField("sourceUnit", n.sourceUnit);
        writeNodeField("composedPolicies", n.composedPolicies);
        return null;
    }

    @Override
    public Void visit(PolicyMethod n) {
        writeNodeStart("PolicyMethod", 3);
        writeNodeField("methodName", n.methodName);
        writeNodeField("parameters", n.parameters);
        writeNodeField("returnSlots", n.returnSlots);
        return null;
    }

    @Override
    public Void visit(Block n) {
        writeNodeStart("Block", 1);
        writeNodeField("statements", n.statements);
        return null;
    }

    @Override
    public Void visit(Assignment n) {
        writeNodeStart("Assignment", 3);
        writeNodeField("left", n.left);
        writeNodeField("right", n.right);
        writeNodeField("isDeclaration", Boolean.valueOf(n.isDeclaration));
        return null;
    }

    @Override
    public Void visit(Var n) {
        writeNodeStart("Var", 3);
        writeNodeField("name", n.name);
        writeNodeField("value", n.value);
        writeNodeField("explicitType", n.explicitType);
        return null;
    }

    @Override
    public Void visit(StmtIf n) {
        writeNodeStart("StmtIf", 3);
        writeNodeField("condition", n.condition);
        writeNodeField("thenBlock", n.thenBlock);
        writeNodeField("elseBlock", n.elseBlock);
        return null;
    }

    @Override
    public Void visit(ExprIf n) {
        writeNodeStart("ExprIf", 3);
        writeNodeField("condition", n.condition);
        writeNodeField("thenExpr", n.thenExpr);
        writeNodeField("elseExpr", n.elseExpr);
        return null;
    }

    @Override
    public Void visit(For n) {
        writeNodeStart("For", 4);
        writeNodeField("iterator", n.iterator);
        writeNodeField("range", n.range);
        writeNodeField("arraySource", n.arraySource);
        writeNodeField("body", n.body);
        return null;
    }

    @Override
    public Void visit(Skip n) {
        writeNodeStart("Skip", 0);
        return null;
    }

    @Override
    public Void visit(Break n) {
        writeNodeStart("Break", 0);
        return null;
    }

    @Override
    public Void visit(Range n) {
        writeNodeStart("Range", 3);
        writeNodeField("step", n.step);
        writeNodeField("start", n.start);
        writeNodeField("end", n.end);
        return null;
    }

    @Override
    public Void visit(Fin n) {
        writeNodeStart("Fin", 0);
        return null;
    }

    @Override
    public Void visit(Tuple n) {
        writeNodeStart("Tuple", 1);
        writeNodeField("elements", n.elements);
        return null;
    }

    @Override
    public Void visit(ReturnSlotAssignment n) {
        writeNodeStart("ReturnSlotAssignment", 3);
        writeNodeField("variableNames", n.variableNames);
        writeNodeField("methodCall", n.methodCall);
        writeNodeField("lambda", n.lambda);
        return null;
    }

    @Override
    public Void visit(SlotDeclaration n) {
        writeNodeStart("SlotDeclaration", 1);
        writeNodeField("slotNames", n.slotNames);
        return null;
    }

    @Override
    public Void visit(SlotAssignment n) {
        writeNodeStart("SlotAssignment", 2);
        writeNodeField("slotName", n.slotName);
        writeNodeField("value", n.value);
        return null;
    }

    @Override
    public Void visit(MultipleSlotAssignment n) {
        writeNodeStart("MultipleSlotAssignment", 1);
        writeNodeField("assignments", n.assignments);
        return null;
    }

    @Override
    public Void visit(BinaryOp n) {
        writeNodeStart("BinaryOp", 3);
        writeNodeField("left", n.left);
        writeNodeField("op", n.op);
        writeNodeField("right", n.right);
        return null;
    }

    @Override
    public Void visit(Unary n) {
        writeNodeStart("Unary", 2);
        writeNodeField("op", n.op);
        writeNodeField("operand", n.operand);
        return null;
    }

    @Override
    public Void visit(TypeCast n) {
        writeNodeStart("TypeCast", 2);
        writeNodeField("targetType", n.targetType);
        writeNodeField("expression", n.expression);
        return null;
    }

    @Override
    public Void visit(MethodCall n) {
        writeNodeStart("MethodCall", 12);
        writeNodeField("name", n.name);
        writeNodeField("qualifiedName", n.qualifiedName);
        writeNodeField("arguments", n.arguments);
        writeNodeField("slotNames", n.slotNames);
        writeNodeField("argNames", n.argNames);
        writeNodeField("isSuperCall", Boolean.valueOf(n.isSuperCall));
        writeNodeField("isGlobal", Boolean.valueOf(n.isGlobal));
        writeNodeField("target", n.target);
        writeNodeField("isSingleSlotCall", Boolean.valueOf(n.isSingleSlotCall));
        writeNodeField("isSelfCall", Boolean.valueOf(n.isSelfCall));
        writeNodeField("selfCallLevel", n.selfCallLevel);
        writeNodeField("selfCallLevelConstantName", n.selfCallLevelConstantName);
        return null;
    }

    @Override
    public Void visit(cod.ast.node.Array n) {
        writeNodeStart("Array", 2);
        writeNodeField("elements", n.elements);
        writeNodeField("elementType", n.elementType);
        return null;
    }

    @Override
    public Void visit(IndexAccess n) {
        writeNodeStart("IndexAccess", 2);
        writeNodeField("array", n.array);
        writeNodeField("index", n.index);
        return null;
    }

    @Override
    public Void visit(RangeIndex n) {
        writeNodeStart("RangeIndex", 3);
        writeNodeField("step", n.step);
        writeNodeField("start", n.start);
        writeNodeField("end", n.end);
        return null;
    }

    @Override
    public Void visit(MultiRangeIndex n) {
        writeNodeStart("MultiRangeIndex", 1);
        writeNodeField("ranges", n.ranges);
        return null;
    }

    @Override
    public Void visit(EqualityChain n) {
        writeNodeStart("EqualityChain", 4);
        writeNodeField("left", n.left);
        writeNodeField("operator", n.operator);
        writeNodeField("isAllChain", Boolean.valueOf(n.isAllChain));
        writeNodeField("chainArguments", n.chainArguments);
        return null;
    }

    @Override
    public Void visit(BooleanChain n) {
        writeNodeStart("BooleanChain", 2);
        writeNodeField("isAll", Boolean.valueOf(n.isAll));
        writeNodeField("expressions", n.expressions);
        return null;
    }

    @Override
    public Void visit(Slot n) {
        writeNodeStart("Slot", 2);
        writeNodeField("name", n.name);
        writeNodeField("type", n.type);
        return null;
    }

    @Override
    public Void visit(Lambda n) {
        writeNodeStart("Lambda", 5);
        writeNodeField("parameters", n.parameters);
        writeNodeField("returnSlots", n.returnSlots);
        writeNodeField("body", n.body);
        writeNodeField("expressionBody", n.expressionBody);
        writeNodeField("inferParameters", Boolean.valueOf(n.inferParameters));
        return null;
    }

    @Override
    public Void visit(Identifier n) {
        writeNodeStart("Identifier", 1);
        writeNodeField("name", n.name);
        return null;
    }

    @Override
    public Void visit(IntLiteral n) {
        writeNodeStart("IntLiteral", 1);
        writeNodeField("value", n.value);
        return null;
    }

    @Override
    public Void visit(FloatLiteral n) {
        writeNodeStart("FloatLiteral", 1);
        writeNodeField("value", n.value);
        return null;
    }

    @Override
    public Void visit(TextLiteral n) {
        writeNodeStart("TextLiteral", 2);
        writeNodeField("value", n.value);
        writeNodeField("isInterpolated", Boolean.valueOf(n.isInterpolated));
        return null;
    }

    @Override
    public Void visit(BoolLiteral n) {
        writeNodeStart("BoolLiteral", 1);
        writeNodeField("value", Boolean.valueOf(n.value));
        return null;
    }

    @Override
    public Void visit(NoneLiteral n) {
        writeNodeStart("NoneLiteral", 0);
        return null;
    }

    @Override
    public Void visit(This n) {
        writeNodeStart("This", 1);
        writeNodeField("className", n.className);
        return null;
    }

    @Override
    public Void visit(Super n) {
        writeNodeStart("Super", 0);
        return null;
    }

    @Override
    public Void visit(ChainedComparison n) {
        writeNodeStart("ChainedComparison", 2);
        writeNodeField("expressions", n.expressions);
        writeNodeField("operators", n.operators);
        return null;
    }

    @Override
    public Void visit(PropertyAccess n) {
        writeNodeStart("PropertyAccess", 2);
        writeNodeField("left", n.left);
        writeNodeField("right", n.right);
        return null;
    }

    @Override
    public List<Void> visitList(List<? extends Base> nodes) {
        List<Void> results = new ArrayList<Void>();
        for (Base n : nodes) {
            results.add(visit(n));
        }
        return results;
    }

    @Override
    public void visitAll(List<? extends Base> nodes) {
        for (Base n : nodes) {
            visit(n);
        }
    }

    @Override
    public Void visit(Expr n) {
        if (n instanceof ArgumentList) {
            ArgumentList args = (ArgumentList) n;
            writeNodeStart("ArgumentList", 1);
            writeNodeField("arguments", args.arguments);
            return null;
        }
        throw new SerializationException(new IOException("Unsupported IR expr node type: " + n.getClass().getName()));
    }

    @Override
    public Void visit(Base n) {
        throw new SerializationException(new IOException("Unsupported IR node type: " + n.getClass().getName()));
    }
}
