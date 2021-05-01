package CodeGenerator;

import AST.*;
import Utilities.Visitor;

class AllocateAddresses extends Visitor {

	private Generator gen;
	private ClassDecl currentClass;

	AllocateAddresses(Generator g, ClassDecl currentClass, boolean debug) {
		this.debug = debug;
		gen = g;
		this.currentClass = currentClass;
	}

	// LOCAL VARIABLE DECLARATION
	public Object visitLocalDecl(LocalDecl ld) {
		// YOUR CODE HERE
		ld.address = gen.getAddress();
		if (ld.type().isLongType() || ld.type().isDoubleType()) {
			gen.inc2Address(); 
		}
		else {
			gen.incAddress();
		}
		//super.visitLocalDecl(ld);
		//ld.localsUsed = gen.getAddress();
		println(ld.line + ": LocalDecl:\tAssigning address:  " + ld.address + " to local variable '" + ld.var().name().getname() + "'.");
		return null;
	}

	// PARAMETER DECLARATION
	public Object visitParamDecl(ParamDecl pd) {
		// YOUR CODE HERE

		pd.address = gen.getAddress();

		if (pd.type().isDoubleType() || pd.type().isLongType()) {
			gen.inc2Address();
		} else {
			gen.incAddress();
		}

		println(pd.line + ": ParamDecl:\tAssigning address:  " + pd.address + " to parameter '" + pd.paramName().getname() + "'.");
		return null;
	}

	// METHOD DECLARATION
	public Object visitMethodDecl(MethodDecl md) {
		println(md.line + ": MethodDecl:\tResetting address counter for method '" + md.name().getname() + "'.");
		// YOUR CODE HERE
		if (md.isStatic()) {
			gen.setAddress(0);
		}
		else {
			gen.setAddress(1);
		}
		super.visitMethodDecl(md);
		md.localsUsed = gen.getAddress();
		println(md.line + ": End MethodDecl");	
		return null;
	}


	// CONSTRUCTOR DECLARATION
	public Object visitConstructorDecl(ConstructorDecl cd) {	
		println(cd.line + ": ConstructorDecl:\tResetting address counter for constructor '" + cd.name().getname() + "'.");
		gen.setAddress(1);
		super.visitConstructorDecl(cd);
		cd.localsUsed = gen.getAddress();
		println(cd.line + ": End ConstructorDecl");
		return null;
	}

	// STATIC INITIALIZER
	public Object visitStaticInitDecl(StaticInitDecl si) {
		println(si.line + ": StaticInit:\tResetting address counter for static initializer for class '" + currentClass.name() + "'.");
		// YOUR CODE HERE
		gen.setAddress(0);
		super.visitStaticInitDecl(si);
		si.localsUsed = gen.getAddress();
		println(si.line + ": End StaticInit");
		return null;
	}
}

