package ast;
import compiler.Failure;

/** Captures information about the layout of the frame for a given
 *  function, including details about register use as well as stack
 *  layout.
 */
public class Frame {

    /** Records the list of formal parameters for the corresponding
     *  function.
     */
    private Formal[] formals;

    /** Holds an environment describing the mappings from variables
     *  to locations in the current stack frame.
     */
    private LocEnv env;

    /** Holds the register map for this layout.
     */
    private Reg[] regmap;

    /** Map from logical register number to physical register.
     */
    private Reg reg(int n) {
        return regmap[n % regmap.length];
    }

    /** Holds the index of the first parameter register.
     */
    private int paramBase;

    /** Holds the index of the first free register.
     */
    private int freeBase;

    /** Holds the index of the current free register.
     */
    private int free;

    /** Holds the number of bytes that are currently pushed on
     *  the stack.
     */
    private int pushed;

    /** Construct a new Frame Layout object for a function with the
     *  given list of formal parameters and the given environment
     *  describing global variables.
     */
    public Frame(Formal[] formals, LocEnv globals) {
        this.formals = formals;
        this.env     = globals;
        // Initialize the register map, including paramBase,
        // freeBase, and free:
        regmap = new Reg[Reg.calleeSaves.length
                       + Reg.args.length
                       + Reg.results.length
                       + Reg.callerSaves.length];
        int r  = 0;
        int i;
        // Callee Saves Register are considered "in use" from the
        // start of the function.
        for (i=0; i<Reg.calleeSaves.length; i++) {
            regmap[r++] = Reg.calleeSaves[i];
        }

        // Next come the registers that are used to supply parameters.
        // These registers also contribute entries to the environment.
        paramBase = r;
        for (i=0; i<Reg.args.length && i<formals.length; i++) {
            regmap[r++] = Reg.args[i];
            env         = new RegEnv(formals[i].getName(), env, Reg.args[i]);
        }

        // Any formal parameters that did not fit in registers will be
        // found on the stack at positive offsets from the base pointer,
        // and will require corresponding entries in the stack frame.
        for (int j=i; j<formals.length; j++) {
            int offset = (2+j-i)*Assembly.QUADSIZE;
            env = new FrameEnv(formals[j].getName(), env, offset);
        }
        
        // Any remaining registers are considerd free for use, starting
        // with the result register(s):
        freeBase = free = r;
        for (int j=0; j<Reg.results.length; j++) {
            regmap[r++] = Reg.results[j];
        }
        // Followed by any unused argument registers:
        for (; i<Reg.args.length; i++) {
            regmap[r++] = Reg.args[i];
        }
        // And then any callerSaves registers:
        for (i=0; i<Reg.callerSaves.length; i++) {
            regmap[r++] = Reg.callerSaves[i];
        }
        // If we need any registers beyond this, we will need to wrap around
        // and start using the callee saves registers at the start of the
        // register map.  (With appropriate spilling, of course.)

        // We start without any bytes pushed on the frame:
        pushed = 0;
    }

    /** Return the current free register.
     */
    public Reg free() { return reg(free); }

    /** Return the name of the current 32 bit register.
     */
    public String free32() { return free().r32(); }

    /** Return the name of the current 64 bit register.
     */
    public String free64() { return free().r64(); }

    /** Save the value in the current free 32 bit register in
     *  the location corresponding to a particular value.
     */
    void store32(Assembly a, String lhs) {
        a.emit("movl", free32(), env.find(lhs).loc32(a));
    }

    /** Load a value from a location corresponding to a particular
     *  variable into the current free 32 bit register.
     */
    void load32(Assembly a, String name) {
        a.emit("movl", env.find(name).loc32(a), free32());
    }

    /** Make the next available register free, spilling the contents
     *  of that register on to the stack if it was already in use.
     *  Every call to spill() must also be paired with a correponding
     *  call to unspill().
     */
    public Reg spill(Assembly a) {
        free++;
        Reg r = reg(free);
        // Save old register value if necessary:
        if (free>=regmap.length) {
            // Save register on the stack
            a.emit("pushq", r.r64());
            pushed += Assembly.QUADSIZE;

            // If we just spilled a formal parameter, update
            // the environment to reflect that.
            int n = free - (regmap.length + paramBase);
            if (n>=0 && n<formals.length && n<Reg.args.length) {
                env = new FrameEnv(formals[n].getName(), env, -pushed);
            }
        }
        return r;
    }

    /** Spill, as necessary, to ensure that the next free register is
     *  available for use, returning the associated 32 bit register
     *  name as a result.
     */
    public String spill32(Assembly a) { return spill(a).r32(); }

    /** Spill, as necessary, to ensure that the next free register is
     *  available for use, returning the associated 32 bit register
     *  name as a result.
     */
    public String spill64(Assembly a) { return spill(a).r64(); }

    /** Release the current free register, potentially unspilling a
     *  previously saved value for the underlying physical memory
     *  from the stack.  Pairs with a previous call to spill().
     */
    public void unspill(Assembly a) {
        Reg r = reg(free);
        if (free>=regmap.length) {
            // Restore saved register value:
            a.emit("popq", r.r64());
            pushed -= Assembly.QUADSIZE;

            // If we just unspilled a formal parameter, update
            // the environment to reflect that.
            int n = free - (regmap.length + paramBase);
            if (n>=0 && n<formals.length && n<Reg.args.length) {
                env = env.next();
            }
        }
        free--;
    }

    /** Return the environment at this point in the code.
     */
    public LocEnv getEnv() { return env; }

    /** Allocate space on the stack for a local variable.
     */
    public void allocLocal(Assembly a, String name, String src) {
        a.emit("pushq", src);
        pushed += Assembly.QUADSIZE;
        env = new FrameEnv(name, env, -pushed);
    }

    /** Reset the stack pointer to a previous position at the end
     *  of a block, decrementing the stack pointer as necessary
     *  and removing items from the environment to reflect local
     *  variables going out of scope.
     */
    public void resetTo(Assembly a, LocEnv origEnv) {
        for (; env!=origEnv; env=env.next()) {
            pushed -= Assembly.QUADSIZE;
            a.insertAdjust(-Assembly.QUADSIZE);
        }
    }

    /** Add some number of bytes at the top of the stack, typically
     *  to meet some alignment constraint.
     */
    public void insertAdjust(Assembly a, int adjust) {
        pushed += adjust;
        a.insertAdjust(adjust);
    }

    /** Dump a description of this frame.
     */
    public void dump(Assembly a) {
        a.emit("# Registers: (free = " + free64() + ")");
        StringBuffer b = new StringBuffer("# ");
        int i = 0;
        for (; i<paramBase; i++) {
            b.append(" ");
            b.append(regmap[i].r64());
        }
        b.append(" <");
        for (; i<freeBase; i++) {
            b.append(" ");
            b.append(regmap[i].r64());
        }
        b.append(" >");
        for (; i<regmap.length; i++) {
            b.append(" ");
            b.append(regmap[i].r64());
        }
        a.emit(b.toString());
        a.emit("#");
        a.emit("# Pushed on stack: " + pushed);
        b = new StringBuffer("# Environment:");
        for (LocEnv env=this.env; env!=null; env=env.next()) {
            String n = env.name;
            String s = env.loc32(a);
            b.append(" ");
            b.append(n);
            if (!n.equals(s)) {
               b.append("->" + s);
            }
        }
        a.emit(b.toString());
        a.emit("#");
    }
}
