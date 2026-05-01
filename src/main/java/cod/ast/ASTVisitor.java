package cod.ast;

import cod.ast.node.*;
import java.util.ArrayList;
import java.util.List;

public abstract class ASTVisitor<T> implements VisitorImpl<T> {

  protected T defaultVisit(Base n) {
    return null;
  }

  @Override
  public T visit(Program n) {
    return n.accept(this);
  }

  @Override
  public T visit(Unit n) {
    return n.accept(this);
  }

  @Override
  public T visit(Use n) {
    return n.accept(this);
  }

  @Override
  public T visit(Type n) {
    return n.accept(this);
  }

  @Override
  public T visit(Policy n) {
    return n.accept(this);
  }

  @Override
  public T visit(PolicyMethod n) {
    return n.accept(this);
  }

  @Override
  public T visit(Field n) {
    return n.accept(this);
  }

  @Override
  public T visit(Method n) {
    return n.accept(this);
  }

  @Override
  public T visit(Param n) {
    return n.accept(this);
  }

  @Override
  public T visit(Constructor n) {
    return n.accept(this);
  }

  @Override
  public T visit(ConstructorCall n) {
    return n.accept(this);
  }

  @Override
  public T visit(Block n) {
    return n.accept(this);
  }

  @Override
  public T visit(Assignment n) {
    return n.accept(this);
  }

  @Override
  public T visit(Var n) {
    return n.accept(this);
  }

  @Override
  public T visit(StmtIf n) {
    return n.accept(this);
  }

  @Override
  public T visit(ExprIf n) {
    return n.accept(this);
  }

  @Override
  public T visit(For n) {
    return n.accept(this);
  }

  @Override
  public T visit(Skip n) {
    return n.accept(this);
  }

  @Override
  public T visit(Break n) {
    return n.accept(this);
  }

  @Override
  public T visit(Range n) {
    return n.accept(this);
  }

  @Override
  public T visit(Exit n) {
    return n.accept(this);
  }

  @Override
  public T visit(Tuple n) {
    return n.accept(this);
  }

  @Override
  public T visit(ReturnSlotAssignment n) {
    return n.accept(this);
  }

  @Override
  public T visit(SlotDeclaration n) {
    return n.accept(this);
  }

  @Override
  public T visit(SlotAssignment n) {
    return n.accept(this);
  }

  @Override
  public T visit(MultipleSlotAssignment n) {
    return n.accept(this);
  }

  @Override
  public T visit(Expr n) {
    return n.accept(this);
  }

  @Override
  public T visit(BinaryOp n) {
    return defaultVisit(n);
  }

  @Override
  public T visit(Unary n) {
    return n.accept(this);
  }

  @Override
  public T visit(TypeCast n) {
    return n.accept(this);
  }

  @Override
  public T visit(MethodCall n) {
    return n.accept(this);
  }

  @Override
  public T visit(Array n) {
    return n.accept(this);
  }

  @Override
  public T visit(IndexAccess n) {
    return n.accept(this);
  }

  @Override
  public T visit(RangeIndex n) {
    return n.accept(this);
  }

  @Override
  public T visit(MultiRangeIndex n) {
    return n.accept(this);
  }

  @Override
  public T visit(EqualityChain n) {
    return n.accept(this);
  }

  @Override
  public T visit(BooleanChain n) {
    return n.accept(this);
  }

  @Override
  public T visit(Slot n) {
    return n.accept(this);
  }

  @Override
  public T visit(Lambda n) {
    return n.accept(this);
  }
  
  @Override
public T visit(PropertyAccess n) {
    return n.accept(this);
}

@Override
public T visit(Identifier n) {
    return n.accept(this);
}

@Override
public T visit(IntLiteral n) {
    return n.accept(this);
}

@Override
public T visit(FloatLiteral n) {
    return n.accept(this);
}

@Override
public T visit(TextLiteral n) {
    return n.accept(this);
}

@Override
public T visit(BoolLiteral n) {
    return n.accept(this);
}

@Override
public T visit(NoneLiteral n) {
    return n.accept(this);
}

@Override
public T visit(This n) {
    return n.accept(this);
}

@Override
public T visit(Super n) {
    return n.accept(this);
}

@Override
public T visit(ChainedComparison n) {
    return defaultVisit(n);
}

  @Override
  public final T visit(Base n) {
    // This is the entry point - delegate to the n's accept method
    return n.accept(this);
  }

  @Override
  public List<T> visitList(List<? extends Base> nodes) {
    List<T> results = new ArrayList<T>();
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

  // Helper method to dispatch via accept() - this is what should be used in InterpreterVisitor
  public T dispatch(Base n) {
    return n.accept(this);
  }
}
