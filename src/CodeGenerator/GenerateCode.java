package CodeGenerator;

import AST.*;
import Utilities.Error;
import Utilities.Visitor;

import java.util.*;

import Instruction.*;
import Jasmin.*;

class GenerateCode extends Visitor {

	private Generator gen;
	private ClassDecl currentClass;
	private boolean insideLoop = false;
	private boolean insideSwitch = false;
	private ClassFile classFile;
	private boolean RHSofAssignment = false;
	private boolean StringBuilderCreated = false;
	
	
	public GenerateCode(Generator g, boolean debug) {
		gen = g;
		this.debug = debug;
		classFile = gen.getClassFile();
	}

	public void setCurrentClass(ClassDecl cd) {
		this.currentClass = cd;
	}

	// ARRAY VISITORS START HERE

	/** ArrayAccessExpr */
	public Object visitArrayAccessExpr(ArrayAccessExpr ae) {
		println(ae.line + ": Visiting ArrayAccessExpr");
		classFile.addComment(ae, "ArrayAccessExpr");
		// YOUR CODE HERE
		classFile.addComment(ae,"End ArrayAccessExpr");
		return null;
	}

	/** ArrayLiteral */
	public Object visitArrayLiteral(ArrayLiteral al) {
		println(al.line + ": Visiting an ArrayLiteral ");
		// YOUR CODE HERE
		return null;
	}

	/** NewArray */
	public Object visitNewArray(NewArray ne) {
		println(ne.line + ": NewArray:\t Creating new array of type " + ne.type.typeName());
		// YOUR CODE HERE
		return null;
	}

	// END OF ARRAY VISITORS

	// ASSIGNMENT
	public Object visitAssignment(Assignment as) {
		println(as.line + ": Assignment:\tGenerating code for an Assignment.");
		classFile.addComment(as, "Assignment");
		/* If a reference is needed then compute it
	          (If array type then generate reference to the	target & index)
	          - a reference is never needed if as.left() is an instance of a NameExpr
	          - a reference can be computed for a FieldRef by visiting the target
	          - a reference can be computed for an ArrayAccessExpr by visiting its target 
		 */
		if (as.left() instanceof FieldRef) {
			println(as.line + ": Generating reference for FieldRef target ");
			FieldRef fr= (FieldRef)as.left();
			fr.target().visit(this);		
			// if the target is a New and the field is static, then the reference isn't needed, so pop it! 
			if (fr.myDecl.isStatic()) // && fr.target() instanceof New) // 3/10/2017 - temporarily commented out
			    // issue pop if target is NOT a class name.
			    if (fr.target() instanceof NameExpr && (((NameExpr)fr.target()).myDecl instanceof ClassDecl))
				;
			    else
				classFile.addInstruction(new Instruction(RuntimeConstants.opc_pop));			
		} else if (as.left() instanceof ArrayAccessExpr) {
			println(as.line + ": Generating reference for Array Access target");
			ArrayAccessExpr ae = (ArrayAccessExpr)as.left();
			classFile.addComment(as, "ArrayAccessExpr target");
			ae.target().visit(this);
			classFile.addComment(as, "ArrayAccessExpr index");
			ae.index().visit(this);
		}

		/* If the assignment operator is <op>= then
	            -- If the left hand side is a non-static field (non array): dup (object ref) + getfield
	            -- If the left hand side is a static field (non array): getstatic   
	            -- If the left hand side is an array reference: dup2 +	Xaload 
				-- If the left hand side is a local (non array): generate code for it: Xload Y 
		 */	        
		if (as.op().kind != AssignmentOp.EQ) {
			if (as.left() instanceof FieldRef) {
				println(as.line + ": Duplicating reference and getting value for LHS (FieldRef/<op>=)");
				FieldRef fr = (FieldRef)as.left();
				if (!fr.myDecl.isStatic()) {
					classFile.addInstruction(new Instruction(RuntimeConstants.opc_dup));
					classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_getfield, fr.targetType.typeName(),
							fr.fieldName().getname(), fr.type.signature()));
				} else 
					classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_getstatic, fr.targetType.typeName(),
							fr.fieldName().getname(), fr.type.signature()));
			} else if (as.left() instanceof ArrayAccessExpr) {
				println(as.line + ": Duplicating reference and getting value for LHS (ArrayAccessRef/<op>=)");
				ArrayAccessExpr ae = (ArrayAccessExpr)as.left();
				classFile.addInstruction(new Instruction(RuntimeConstants.opc_dup2));
				classFile.addInstruction(new Instruction(Generator.getArrayLoadInstruction(ae.type)));
			} else { // NameExpr
				println(as.line + ": Getting value for LHS (NameExpr/<op>=)");
				NameExpr ne = (NameExpr)as.left();
				int address = ((VarDecl)ne.myDecl).address();

				if (address < 4)
					classFile.addInstruction(new Instruction(Generator.getLoadInstruction(((VarDecl)ne.myDecl).type(), address, true)));
				else
					classFile.addInstruction(new SimpleInstruction(Generator.getLoadInstruction(((VarDecl)ne.myDecl).type(), address, true), address));
			}
		}

		/* Visit the right hand side (RHS) */
		boolean oldRHSofAssignment = RHSofAssignment;
		RHSofAssignment = true;
		as.right().visit(this);
		RHSofAssignment = oldRHSofAssignment;
		/* Convert the right hand sides type to that of the entire assignment */

		if (as.op().kind != AssignmentOp.LSHIFTEQ &&
		    as.op().kind != AssignmentOp.RSHIFTEQ &&
		    as.op().kind != AssignmentOp.RRSHIFTEQ)
		    gen.dataConvert(as.right().type, as.type);

		/* If the assignment operator is <op>= then
				- Execute the operator
		 */
		if (as.op().kind != AssignmentOp.EQ)
			classFile.addInstruction(new Instruction(Generator.getBinaryAssignmentOpInstruction(as.op(), as.type)));

		/* If we are the right hand side of an assignment
		     -- If the left hand side is a non-static field (non array): dup_x1/dup2_x1
			 -- If the left hand side is a static field (non array): dup/dup2
			 -- If the left hand side is an array reference: dup_x2/dup2_x2 
			 -- If the left hand side is a local (non array): dup/dup2 
		 */    
		if (RHSofAssignment) {
			String dupInstString = "";
			if (as.left() instanceof FieldRef) {
				FieldRef fr = (FieldRef)as.left();
				if (!fr.myDecl.isStatic())  
					dupInstString = "dup" + (fr.type.width() == 2 ? "2" : "") + "_x1";
				else 
					dupInstString = "dup" + (fr.type.width() == 2 ? "2" : "");
			} else if (as.left() instanceof ArrayAccessExpr) {
				ArrayAccessExpr ae = (ArrayAccessExpr)as.left();
				dupInstString = "dup" + (ae.type.width() == 2 ? "2" : "") + "_x2";
			} else { // NameExpr
				NameExpr ne = (NameExpr)as.left();
				dupInstString = "dup" + (ne.type.width() == 2 ? "2" : "");
			}
			classFile.addInstruction(new Instruction(Generator.getOpCodeFromString(dupInstString)));
		}

		/* Store
		     - If LHS is a field: putfield/putstatic
			 -- if LHS is an array reference: Xastore 
			 -- if LHS is a local: Xstore Y
		 */
		if (as.left() instanceof FieldRef) {
			FieldRef fr = (FieldRef)as.left();
			if (!fr.myDecl.isStatic()) 
				classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_putfield,
						fr.targetType.typeName(), fr.fieldName().getname(), fr.type.signature()));
			else 
				classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_putstatic,
						fr.targetType.typeName(), fr.fieldName().getname(), fr.type.signature()));
		} else if (as.left() instanceof ArrayAccessExpr) {
			ArrayAccessExpr ae = (ArrayAccessExpr)as.left();
			classFile.addInstruction(new Instruction(Generator.getArrayStoreInstruction(ae.type)));
		} else { // NameExpr				
			NameExpr ne = (NameExpr)as.left();
			int address = ((VarDecl)ne.myDecl).address() ;

			// CHECK!!! TODO: changed 'true' to 'false' in these getStoreInstruction calls below....
			if (address < 4)
				classFile.addInstruction(new Instruction(Generator.getStoreInstruction(((VarDecl)ne.myDecl).type(), address, false)));
			else {
				classFile.addInstruction(new SimpleInstruction(Generator.getStoreInstruction(((VarDecl)ne.myDecl).type(), address, false), address));
			}
		}
		classFile.addComment(as, "End Assignment");
		return null;
	}

	// BINARY EXPRESSION
    public Object visitBinaryExpr(BinaryExpr be) {
	println(be.line + ": BinaryExpr:\tGenerating code for " + be.op().operator() + " :  " + be.left().type.typeName() + " -> " + be.right().type.typeName() + " -> " + be.type.typeName() + ".");
	classFile.addComment(be, "Binary Expression");
		
	// YOUR CODE HERE
	classFile.addComment(be, "End BinaryExpr");
	return null;
    }

    // BREAK STATEMENT
    public Object visitBreakStat(BreakStat br) {
	println(br.line + ": BreakStat:\tGenerating code.");
	classFile.addComment(br, "Break Statement");

	// YOUR CODE HERE

	classFile.addComment(br, "End BreakStat");
	return null;
    }

    // CAST EXPRESSION
    public Object visitCastExpr(CastExpr ce) {
	println(ce.line + ": CastExpr:\tGenerating code for a Cast Expression.");
	classFile.addComment(ce, "Cast Expression");
	String instString;
	// YOUR CODE HERE
	classFile.addComment(ce, "End CastExpr");
	return null;
    }
    
	// CONSTRUCTOR INVOCATION (EXPLICIT)
	public Object visitCInvocation(CInvocation ci) {
		println(ci.line + ": CInvocation:\tGenerating code for Explicit Constructor Invocation.");     
		classFile.addComment(ci, "Explicit Constructor Invocation");

		// YOUR CODE HERE

		classFile.addInstruction(new Instruction(RuntimeConstants.opc_aload_0));
		classFile.addInstruction(new MethodInvocationInstruction(RuntimeConstants.opc_invokespecial, ci.targetClass.name(), "<init>()V", ci.constructor.paramSignature()));

		classFile.addComment(ci, "End CInvocation");
		return null;
	}

	// CLASS DECLARATION
	public Object visitClassDecl(ClassDecl cd) {
		println(cd.line + ": ClassDecl:\tGenerating code for class '" + cd.name() + "'.");

		// We need to set this here so we can retrieve it when we generate
		// field initializers for an existing constructor.
		currentClass = cd;

		// YOUR CODE HERE
		cd.constructors.visit(this);

		return null;
	}

	// CONSTRUCTOR DECLARATION
	public Object visitConstructorDecl(ConstructorDecl cd) {
		println(cd.line + ": ConstructorDecl: Generating Code for constructor for class " + cd.name().getname());

		classFile.startMethod(cd);
		classFile.addComment(cd, "Constructor Declaration");

		// 12/05/13 = removed if (just in case this ever breaks ;-) )
		cd.cinvocation().visit(this);

		// YOUR CODE HERE

		classFile.addComment(cd, "Field Init Generation Start");
		new GenerateFieldInits(gen, currentClass, false);
		classFile.addComment(cd, "Field Init Generation End");

		classFile.addInstruction(new Instruction(RuntimeConstants.opc_return));

		// We are done generating code for this method, so transfer it to the classDecl.
		cd.setCode(classFile.getCurrentMethodCode());
		classFile.endMethod();

		return null;
	}


	// CONTINUE STATEMENT
	public Object visitContinueStat(ContinueStat cs) {
		println(cs.line + ": ContinueStat:\tGenerating code.");
		classFile.addComment(cs, "Continue Statement");

		// YOUR CODE HERE

		classFile.addComment(cs, "End ContinueStat");
		return null;
	}

	// DO STATEMENT
	public Object visitDoStat(DoStat ds) {
		println(ds.line + ": DoStat:\tGenerating code.");
		classFile.addComment(ds, "Do Statement");

		// YOUR CODE HERE

		classFile.addComment(ds, "End DoStat");
		return null; 
	}


	// EXPRESSION STATEMENT
	public Object visitExprStat(ExprStat es) {	
		println(es.line + ": ExprStat:\tVisiting an Expression Statement.");
		classFile.addComment(es, "Expression Statement");

		es.expression().visit(this);
		if (es.expression() instanceof Invocation) {
			Invocation in = (Invocation)es.expression();
			System.out.println(in.target() == null);


			if (in.targetType.isStringType() && in.methodName().getname().equals("length")) {
			    println(es.line + ": ExprStat:\tInvocation of method length, return value not uses.");
			    gen.dup(es.expression().type, RuntimeConstants.opc_pop, RuntimeConstants.opc_pop2);
			} else if (in.targetType.isStringType() && in.methodName().getname().equals("charAt")) {
			    println(es.line + ": ExprStat:\tInvocation of method charAt, return value not uses.");
			    gen.dup(es.expression().type, RuntimeConstants.opc_pop, RuntimeConstants.opc_pop2);
			} else if (in.targetMethod.returnType().isVoidType())
				println(es.line + ": ExprStat:\tInvocation of Void method where return value is not used anyways (no POP needed)."); 
			else {
				println(es.line + ": ExprStat:\tPOP added to remove non used return value for a '" + es.expression().getClass().getName() + "'.");
				gen.dup(es.expression().type, RuntimeConstants.opc_pop, RuntimeConstants.opc_pop2);
			}
		}
		else 
			if (!(es.expression() instanceof Assignment)) {
				gen.dup(es.expression().type, RuntimeConstants.opc_pop, RuntimeConstants.opc_pop2);
				println(es.line + ": ExprStat:\tPOP added to remove unused value left on stack for a '" + es.expression().getClass().getName() + "'.");
			}
		classFile.addComment(es, "End ExprStat");
		return null;
	}

	// FIELD DECLARATION
	public Object visitFieldDecl(FieldDecl fd) {
		println(fd.line + ": FieldDecl:\tGenerating code.");

		classFile.addField(fd);

		return null;
	}

	// FIELD REFERENCE
	public Object visitFieldRef(FieldRef fr) {
		println(fr.line + ": FieldRef:\tGenerating code (getfield code only!).");

		// Changed June 22 2012 Array
		// If we have and field reference with the name 'length' and an array target type
		if (fr.myDecl == null) { // We had a array.length reference. Not the nicest way to check!!
			classFile.addComment(fr, "Array length");
			fr.target().visit(this);
			classFile.addInstruction(new Instruction(RuntimeConstants.opc_arraylength));
			return null;
		}

		classFile.addComment(fr,  "Field Reference");

		// Note when visiting this node we assume that the field reference
		// is not a left hand side, i.e. we always generate 'getfield' code.

		// Generate code for the target. This leaves a reference on the 
		// stack. pop if the field is static!
		fr.target().visit(this);
		if (!fr.myDecl.modifiers.isStatic()) 
			classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_getfield, 
					fr.targetType.typeName(), fr.fieldName().getname(), fr.type.signature()));
		else {
			// If the target is that name of a class and the field is static, then we don't need a pop; else we do:
			if (!(fr.target() instanceof NameExpr && (((NameExpr)fr.target()).myDecl instanceof ClassDecl))) 
				classFile.addInstruction(new Instruction(RuntimeConstants.opc_pop));
			classFile.addInstruction(new FieldRefInstruction(RuntimeConstants.opc_getstatic,
					fr.targetType.typeName(), fr.fieldName().getname(),  fr.type.signature()));
		}
		classFile.addComment(fr, "End FieldRef");
		return null;
	}


	// FOR STATEMENT
	public Object visitForStat(ForStat fs) {
		println(fs.line + ": ForStat:\tGenerating code.");
		classFile.addComment(fs, "For Statement");
		// YOUR CODE HERE
		classFile.addComment(fs, "End ForStat");	
		return null;
	}

	// IF STATEMENT
	public Object visitIfStat(IfStat is) {
		println(is.line + ": IfStat:\tGenerating code.");
		classFile.addComment(is, "If Statement");

		// YOUR CODE HERE
		classFile.addComment(is,  "End IfStat");
		return null;
	}


	// INVOCATION
	public Object visitInvocation(Invocation in) {
	    println(in.line + ": Invocation:\tGenerating code for invoking method '" + in.methodName().getname() + "' in class '" + in.targetType.typeName() + "'.");
		classFile.addComment(in, "Invocation");
		// YOUR CODE HERE

		classFile.addComment(in, "End Invocation");


		return null;
	}

	// LITERAL
	public Object visitLiteral(Literal li) {
		println(li.line + ": Literal:\tGenerating code for Literal '" + li.getText() + "'.");
		classFile.addComment(li, "Literal");

		switch (li.getKind()) {
		case Literal.ByteKind:
		case Literal.CharKind:
		case Literal.ShortKind:
		case Literal.IntKind:
			gen.loadInt(li.getText());
			break;
		case Literal.NullKind:
			classFile.addInstruction(new Instruction(RuntimeConstants.opc_aconst_null));
			break;
		case Literal.BooleanKind:
			if (li.getText().equals("true")) 
				classFile.addInstruction(new Instruction(RuntimeConstants.opc_iconst_1));
			else
				classFile.addInstruction(new Instruction(RuntimeConstants.opc_iconst_0));
			break;
		case Literal.FloatKind:
			gen.loadFloat(li.getText());
			break;
		case Literal.DoubleKind:
			gen.loadDouble(li.getText());
			break;
		case Literal.StringKind:
			gen.loadString(li.getText());
			break;
		case Literal.LongKind:
			gen.loadLong(li.getText());
			break;	    
		}
		classFile.addComment(li,  "End Literal");
		return null;
	}

	// LOCAL VARIABLE DECLARATION
	public Object visitLocalDecl(LocalDecl ld) {
		if (ld.var().init() != null) {
			println(ld.line + ": LocalDecl:\tGenerating code for the initializer for variable '" + 
					ld.var().name().getname() + "'.");
			classFile.addComment(ld, "Local Variable Declaration");

			// YOUR CODE HERE
			classFile.addComment(ld, "End LocalDecl");
		}
		else
			println(ld.line + ": LocalDecl:\tVisiting local variable declaration for variable '" + ld.var().name().getname() + "'.");

		return null;
	}

	// METHOD DECLARATION
	public Object visitMethodDecl(MethodDecl md) {
		println(md.line + ": MethodDecl:\tGenerating code for method '" + md.name().getname() + "'.");	
		classFile.startMethod(md);

		classFile.addComment(md, "Method Declaration (" + md.name() + ")");

		if (md.block() !=null) 
			md.block().visit(this);
		gen.endMethod(md);
		return null;
	}


	// NAME EXPRESSION
	public Object visitNameExpr(NameExpr ne) {
		classFile.addComment(ne, "Name Expression --");

		// ADDED 22 June 2012 
		if (ne.myDecl instanceof ClassDecl) {
			println(ne.line + ": NameExpr:\tWas a class name - skip it :" + ne.name().getname());
			classFile.addComment(ne, "End NameExpr");
			return null;
		}

		// YOUR CODE HERE

		classFile.addComment(ne, "End NameExpr");
		return null;
	}

	// NEW
	public Object visitNew(New ne) {
		println(ne.line + ": New:\tGenerating code");
		classFile.addComment(ne, "New");

		// YOUR CODE HERE

		classFile.addComment(ne, "End New");
		return null;
	}

	// RETURN STATEMENT
	public Object visitReturnStat(ReturnStat rs) {
		println(rs.line + ": ReturnStat:\tGenerating code.");
		classFile.addComment(rs, "Return Statement");

		// YOUR CODE HERE

		classFile.addComment(rs, "End ReturnStat");
		return null;
	}

	// STATIC INITIALIZER
	public Object visitStaticInitDecl(StaticInitDecl si) {
		println(si.line + ": StaticInit:\tGenerating code for a Static initializer.");	

		classFile.startMethod(si);
		classFile.addComment(si, "Static Initializer");

		// YOUR CODE HERE

		si.setCode(classFile.getCurrentMethodCode());
		classFile.endMethod();
		return null;
	}

	// SUPER
	public Object visitSuper(Super su) {
		println(su.line + ": Super:\tGenerating code (access).");	
		classFile.addComment(su, "Super");

		// YOUR CODE HERE

		classFile.addComment(su, "End Super");
		return null;
	}

	// SWITCH STATEMENT
	public Object visitSwitchStat(SwitchStat ss) {
		println(ss.line + ": Switch Statement:\tGenerating code for Switch Statement.");
		int def = -1;
		SortedMap<Object, SwitchLabel> sm = new TreeMap<Object, SwitchLabel>();
		classFile.addComment(ss,  "Switch Statement");

		SwitchGroup sg = null;
		SwitchLabel sl = null;

		// just to make sure we can do breaks;
		boolean oldinsideSwitch = insideSwitch;
		insideSwitch = true;
		String oldBreakLabel = Generator.getBreakLabel();
		Generator.setBreakLabel("L"+gen.getLabel());

		// Generate code for the item to switch on.
		ss.expr().visit(this);	
		// Write the lookup table
		for (int i=0;i<ss.switchBlocks().nchildren; i++) {
			sg = (SwitchGroup)ss.switchBlocks().children[i];
			sg.setLabel(gen.getLabel());
			for(int j=0; j<sg.labels().nchildren;j++) {
				sl = (SwitchLabel)sg.labels().children[j];
				sl.setSwitchGroup(sg);
				if (sl.isDefault())
					def = i;
				else
					sm.put(sl.expr().constantValue(), sl);
			}
		}

		for (Iterator<Object> ii=sm.keySet().iterator(); ii.hasNext();) {
			sl = sm.get(ii.next());
		}

		// default comes last, if its not there generate an empty one.
		if (def != -1) {
			classFile.addInstruction(new LookupSwitchInstruction(RuntimeConstants.opc_lookupswitch, sm, 
					"L" + ((SwitchGroup)ss.switchBlocks().children[def]).getLabel()));
		} else {
			// if no default label was there then just jump to the break label.
			classFile.addInstruction(new LookupSwitchInstruction(RuntimeConstants.opc_lookupswitch, sm, 
					Generator.getBreakLabel()));
		}

		// Now write the code and the labels.
		for (int i=0;i<ss.switchBlocks().nchildren; i++) {
			sg = (SwitchGroup)ss.switchBlocks().children[i];
			classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, "L"+sg.getLabel()));
			sg.statements().visit(this);
		}

		// Put the break label in;
		classFile.addInstruction(new LabelInstruction(RuntimeConstants.opc_label, Generator.getBreakLabel()));
		insideSwitch = oldinsideSwitch;
		Generator.setBreakLabel(oldBreakLabel);
		classFile.addComment(ss, "End SwitchStat");
		return null;
	}

	// TERNARY EXPRESSION 
	public Object visitTernary(Ternary te) {
		println(te.line + ": Ternary:\tGenerating code.");
		classFile.addComment(te, "Ternary Statement");

		boolean OldStringBuilderCreated = StringBuilderCreated;
		StringBuilderCreated = false;

		// YOUR CODE HERE
		classFile.addComment(te, "Ternary");
		StringBuilderCreated = OldStringBuilderCreated;
		return null;
	}

	// THIS
	public Object visitThis(This th) {
		println(th.line + ": This:\tGenerating code (access).");       
		classFile.addComment(th, "This");

		// YOUR CODE HERE

		classFile.addComment(th, "End This");
		return null;
	}

	// UNARY POST EXPRESSION
	public Object visitUnaryPostExpr(UnaryPostExpr up) {
		println(up.line + ": UnaryPostExpr:\tGenerating code.");
		classFile.addComment(up, "Unary Post Expression");

		// YOUR CODE HERE

		classFile.addComment(up, "End UnaryPostExpr");
		return null;
	}

	// UNARY PRE EXPRESSION
	public Object visitUnaryPreExpr(UnaryPreExpr up) {
		println(up.line + ": UnaryPreExpr:\tGenerating code for " + up.op().operator() + " : " + up.expr().type.typeName() + " -> " + up.expr().type.typeName() + ".");
		classFile.addComment(up,"Unary Pre Expression");

		// YOUR CODE HERE

		classFile.addComment(up, "End UnaryPreExpr");
		return null;
	}

	// WHILE STATEMENT
	public Object visitWhileStat(WhileStat ws) {
		println(ws.line + ": While Stat:\tGenerating Code.");

		classFile.addComment(ws, "While Statement");

		// YOUR CODE HERE

		classFile.addComment(ws, "End WhileStat");	
		return null;
	}
}

