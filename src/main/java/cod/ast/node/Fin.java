package cod.ast.node;

import cod.ast.VisitorImpl;

public class Fin extends Stmt {

           @Override
        public final <T> T accept(VisitorImpl<T> visitor) {
           return visitor.visit(this);
        }
    

}
