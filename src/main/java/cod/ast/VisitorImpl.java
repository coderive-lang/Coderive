package cod.ast;

import cod.ast.node.*;
import java.util.List;

public interface VisitorImpl<T> {

  // Program structure
  T visit(Program n);

  T visit(Unit n);

  T visit(Use n);

  // Type declarations
  T visit(Type n);

  T visit(Field n);

  T visit(Method n);

  T visit(Param n);

  T visit(Constructor n);

  T visit(ConstructorCall n);

  // Policy declarations
  T visit(Policy n);

  T visit(PolicyMethod n);

  // Statements
  T visit(Block n);

  T visit(Assignment n);

  T visit(Var n);

  T visit(StmtIf n);

  T visit(ExprIf n);

  T visit(For n);

  T visit(Skip n);

  T visit(Break n);

  T visit(Range n);

  T visit(Exit n);

  T visit(Tuple n);

  T visit(ReturnSlotAssignment n);

  T visit(SlotDeclaration n);

  T visit(SlotAssignment n);

  T visit(MultipleSlotAssignment n);

  // Expressions
  T visit(Expr n);

  T visit(BinaryOp n);

  T visit(Unary n);

  T visit(TypeCast n);

  T visit(MethodCall n);

  T visit(Array n);

  T visit(IndexAccess n);

  T visit(RangeIndex n);

  T visit(MultiRangeIndex n);

  T visit(EqualityChain n);

  T visit(BooleanChain n);

  T visit(Slot n);

  T visit(Lambda n);

  T visit(Identifier n);

  T visit(IntLiteral n);

  T visit(FloatLiteral n);

  T visit(TextLiteral n);

  T visit(BoolLiteral n);

  T visit(NoneLiteral n);

  T visit(This n);

  T visit(Super n);

  T visit(ChainedComparison n);

  T visit(PropertyAccess n);

  T visit(Base n);

  // Utility methods for visiting lists
  List<T> visitList(List<? extends Base> n);

  void visitAll(List<? extends Base> n);
}
