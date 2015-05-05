package ast;
import compiler.Failure;

/** Abstract syntax for do while statements.
 */
public class DoWhile extends Stmt {

    /** The body of this loop.
     */
    private Stmt body;

    /** The test expression.
     */
    private Expr test;

    /** Default constructor.
     */
    public DoWhile(Stmt body, Expr test) {
        this.body = body;
        this.test = test;
    }

    /** Print an indented description of this abstract syntax node,
     *  including a name for the node itself at the specified level
     *  of indentation, plus more deeply indented descriptions of
     *  any child nodes.
     */
    public void indent(IndentOutput out, int n) {
        out.indent(n, "DoWhile");
        body.indent(out, n+1);
        test.indent(out, n+1);
    }

    /** Type check this statement, using the specified context, with
     *  the given initial typing environment, and returning the typing
     *  environment for a following statement.
     */
    public TypeEnv check(Context ctxt, TypeEnv locals)
      throws Failure {
        try {
            if (!test.typeOf(ctxt, locals).equals(Type.BOOLEAN)) {
                ctxt.report(new Failure("WhileBoolean"));
            }
        } catch (Failure f) {
            ctxt.report(f);
        }
        body.check(ctxt, locals); // discard final environment
        return locals;
    }

    /** Generate code for executing this statement.
     */
    public void compile(Assembly a, Frame f) {
        throw new Error("compile not implemented for DoWhile");
    }
}
