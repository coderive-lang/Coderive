package cod.ast.node;

import cod.ast.VisitorImpl;

public class Exit extends Stmt {

           @Override
        public final <T> T accept(VisitorImpl<T> visitor) {
           return visitor.visit(this);
        }
    

}