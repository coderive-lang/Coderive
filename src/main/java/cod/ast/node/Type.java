package cod.ast.node;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import cod.ast.VisitorImpl;
import cod.lexer.Token;
import cod.lexer.TokenType.Keyword;

public class Type extends Base {
  public String name;
  public Keyword visibility = Keyword.SHARE;
  public String extendName = null;
  public List<Field> fields = new ArrayList<Field>();
  public Constructor constructor;
  public List<Method> methods = new ArrayList<Method>();
  public List<Stmt> statements = new ArrayList<Stmt>();
  public List<Constructor> constructors = new ArrayList<Constructor>();
  public List<String> implementedPolicies = new ArrayList<String>();
  public boolean isUnsafe = false;
  
  // Make Token fields transient
  public transient Token extendToken;
  public transient Token parentToken;
  public transient Map<String, Token> policyTokens;
  // Cache for viral policy checking (optional optimization)
  public List<String> cachedAncestorPolicies = null;
  public boolean viralPoliciesValidated = false;

  @Override
  public final <T> T accept(VisitorImpl<T> visitor) {
    return visitor.visit(this);
  }
}
