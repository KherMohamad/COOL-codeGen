package cool.visitor;

import cool.AST.*;
import cool.compiler.Compiler;
import cool.parser.CoolParser;
import cool.scopes.Scope;
import cool.scopes.SymbolTable;
import cool.symbols.IdSymbol;
import cool.symbols.Symbol;
import cool.symbols.TypeSymbol;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroupFile;
import java.io.File;
import java.util.*;

public class CodeGenVisitor implements ASTVisitor<ST> {
	
	private final STGroupFile templates;
	private int labelCounter;
	private int caseCounter;
	private int esacCounter;
	Scope scope;

	private final List<ST> constants;
	private final List<String> classNames;
	private final List<String> classObjs;
	private final List<String> objectPrototypes;
	private final List<String> dispatchTables;
	private final List<String> methods;

	private final HashMap<String, Integer> strings;
	private final HashSet<Integer> ints;
	private final HashMap<String, HashMap<String, String>> operators;
	private static final String STRCLASSATTRIB = "\t.word\tint_const0\n\t.asciiz\t\"\"\n\t.align\t2";
	private static final String PRIMECLASSATTRIB = "\t.word\t0";

	class TagComparator implements Comparator {
		@Override
		public int compare(Object o1, Object o2) {

			if (o1 instanceof TypeSymbol) {
				TypeSymbol ts1 = (TypeSymbol) o1;
				TypeSymbol ts2 = (TypeSymbol) o2;
				return Integer.compare(ts1.getTag(), ts2.getTag());
			}

			ASTCaseBranchNode cs1 = (ASTCaseBranchNode) o1;
			ASTCaseBranchNode cs2 = (ASTCaseBranchNode) o2;

			return Integer.compare(-cs1.getTypeSymbol().getTag(), -cs2.getTypeSymbol().getTag());
		}
	}

	public TagComparator tagComparator = new TagComparator();

	private String addString(String s) {

		int label;
		if (!strings.containsKey(s)) {
			int len = s.length();

			label = strings.size();
			strings.put(s, label);

			var stringConstant = templates.getInstanceOf("stringConstant");
			stringConstant.add("label", label);
			stringConstant.add("tag", TypeSymbol.STRING.getTag());
			stringConstant.add("str", s);
			String constInt = "int_const" + addInt(len);
			stringConstant.add("len", constInt);
			stringConstant.add("wordCount", (len + 1) / 4 + 5);

			constants.add(stringConstant);

		} else {
			label = strings.get(s);
		}

		return Integer.toString(label);
	}

	private String addInt(int n) {

		if (!ints.contains(n)) {
			ints.add(n);

			ST intConstant = templates.getInstanceOf("intConstant");
			intConstant.add("tag", TypeSymbol.INT.getTag());
			intConstant.add("val", n);
			constants.add(intConstant);
		}

		return Integer.toString(n);
	}

	private String initPrimary(int token, TypeSymbol symbol) {
		if (token == 0) {
			if (symbol == TypeSymbol.STRING) {
				return "\t.word\tstr_const0";
			}
			if (symbol == TypeSymbol.INT) {
				return "\t.word\tint_const0";
			}
			if (symbol == TypeSymbol.BOOL) {
				return "\t.word\tbool_const0";
			}
			return "\t.word\t0";
		}
		if (symbol == TypeSymbol.INT) {
			return "\tla\t\t$a0 int_const0";
		}
		if (symbol == TypeSymbol.STRING) {
			return "\tla\t\t$a0 str_const0";
		}
		if (symbol == TypeSymbol.BOOL) {
			return "\tla\t\t$a0 bool_const0";
		}
			return "\tli\t\t$a0 0";
	}

	private ST addObjectInit(ASTClassNode node) {

		var type = node.getType();

		List<String> attributeList = new LinkedList<>();

		for (ASTClassContentNode cNode : node.getContent()) {

			if (cNode instanceof ASTAttributeNode) {

				var ret = cNode.accept(this);

				if (ret != null) {
					attributeList.add(ret.render() + "\n");
				}
			}
		}
		ST objInit;
		if (type.getParentName() != null) {
			objInit = templates.getInstanceOf("objectInit");
			objInit.add("class", type);
			objInit.add("parent", type.getParentName());
		} else {
			objInit = templates.getInstanceOf("objectInitNoParent");
			objInit.add("class", type);
		}
		objInit.add("attributes", attributeList);

		return objInit;
	}

	public ST getObjectInit(TypeSymbol symbol, STGroupFile templates) {
		if (symbol.getParentName() != null) {
			return templates.getInstanceOf("objectInit")
					.add("class", symbol)
					.add("parent", symbol.getParentName());
		} else {
			return templates.getInstanceOf("objectInitNoParent")
					.add("class", symbol);
		}
	}

	private List<String> getDispatchTableMethods(TypeSymbol symbol) {
		var className = symbol.getName();
		var currentMethods = symbol.getMethods().keySet();
		List<String> dispatchTableMethods = new ArrayList<>();

		if (symbol.getParent() != null) {
			var parentMethods = getDispatchTableMethods((TypeSymbol)symbol.getParent());

			for (String parentMethod : parentMethods) {
				var tokens = parentMethod.split("\\.");
				var methodName = tokens[tokens.length - 1];
				if (currentMethods.contains(methodName)) {
					currentMethods.remove(methodName);
					dispatchTableMethods.add(className + "." + methodName);
				}
				else {
					dispatchTableMethods.add(parentMethod);
				}
			}
		}
		List <String> methodsToAdd = new ArrayList<>();
		for (String method : currentMethods) {
			methodsToAdd.add(className + "." + method);
		}

		dispatchTableMethods.addAll(methodsToAdd);
		return dispatchTableMethods;
	}

	public ST getDispatchTable(TypeSymbol symbol, STGroupFile templates) {
		List<String> dispatchTable = new ArrayList<>();
		var methods = getDispatchTableMethods(symbol);
		for (String method : methods) {
			dispatchTable.add(templates.getInstanceOf("dispatchTableEntry").add("method", method).render());
		}

		String dispatchTableTokens = new String("");
		for (String currElem : dispatchTable) {
			dispatchTableTokens += currElem;
			dispatchTableTokens += '\n';
		}
		return templates.getInstanceOf("dispatchTable")
				.add("class", symbol.getName())
				.add("methods", dispatchTableTokens);
	}

	public String getPrimaryClassAttributes(TypeSymbol symbol) {
		if (symbol == TypeSymbol.INT || symbol == TypeSymbol.BOOL) {
			return PRIMECLASSATTRIB;
		}
		if (symbol == TypeSymbol.STRING) {
			return STRCLASSATTRIB;
		}

		String attributesFinal = "";
		if (symbol.getParent() != null) {
			attributesFinal = getPrimaryClassAttributes((TypeSymbol)symbol.getParent());
		}

		List<String> attributesList = new ArrayList<>();
		for (var currAttribute: symbol.getAttributes().values()) {
			if (!currAttribute.getName().equals("self")) {
				attributesList.add(initPrimary(0, currAttribute.getType()));
			}
		}
		String attributesJoined = String.join("\n", attributesList);



		if (!attributesFinal.isEmpty() && !attributesJoined.isEmpty()) {
			return attributesFinal + "\n" + attributesJoined;
		}
		return attributesFinal + attributesJoined;
	}

	public ST getObjectPrototype(TypeSymbol symbol, STGroupFile templates) {
		int words = 3;
		var type = symbol;

		while (type != null) {
			words += type.getAttributes().size() - 1;
			type = (TypeSymbol)type.getParent();
		}

		return templates.getInstanceOf("protObj")
				.add("class", symbol)
				.add("tag", symbol.getTag())
				.add("words", words)
				.add("attributes", getPrimaryClassAttributes(symbol));
	}

	public CodeGenVisitor() {

		templates = new STGroupFile("cgen.stg");
		strings = new HashMap<>();
		ints = new HashSet<>();
		labelCounter = 0;

		constants = new ArrayList<>();
		classNames = new ArrayList<>();
		classObjs = new ArrayList<>();
		dispatchTables = new ArrayList<>();
		objectPrototypes = new ArrayList<>();
		methods = new ArrayList<>();

		addInt(0);
		addString("");

		ArrayList<Symbol> symbolList = new ArrayList<>(SymbolTable.globals.getSymbols().values());


		Collections.sort(symbolList, tagComparator);

		for (Symbol symbol: symbolList) {

			if (symbol != TypeSymbol.SELF_TYPE) {

				dispatchTables.add(getDispatchTable((TypeSymbol) symbol, templates).render());
				classObjs.add(templates.getInstanceOf("objectTabField").add("class", symbol).render());
				String constStr = "str_const" + addString(symbol.getName());
				classNames.add("\t.word\t" + constStr);
				objectPrototypes.add(getObjectPrototype((TypeSymbol) symbol, templates).render());
				addInt(symbol.getName().length());
			}
		}

		methods.add(getObjectInit(TypeSymbol.OBJECT, templates).render());
		methods.add(getObjectInit(TypeSymbol.INT, templates).render());
		methods.add(getObjectInit(TypeSymbol.BOOL, templates).render());
		methods.add(getObjectInit(TypeSymbol.STRING, templates).render());
		methods.add(getObjectInit(TypeSymbol.IO, templates).render());

		operators = new HashMap<String, HashMap<String, String>>();
		HashMap<String, String> arithmOperators = new HashMap<String, String>();
		arithmOperators.put("+", "add");
		arithmOperators.put("-", "sub");
		arithmOperators.put("*", "mul");
		arithmOperators.put("/", "div");
		operators.put("arithmetic", arithmOperators);

		HashMap<String, String> compOperators = new HashMap<String, String>();
		compOperators.put("<", "blt");
		compOperators.put("<=", "ble");
		operators.put("comparison",compOperators);
	}

	@Override
	public ST visit(ASTProgramNode programNode) {
		for (ASTClassNode classNode : programNode.getClasses()) {
			this.visit(classNode);
		}

		List<String> primary = new ArrayList<>();
		primary.add( templates.getInstanceOf("primaryLabel")
				.add("name", "int")
				.add("tag", TypeSymbol.INT.getTag()).render());
		primary.add(templates.getInstanceOf("primaryLabel")
				.add("name", "bool")
				.add("tag", TypeSymbol.BOOL.getTag()).render());
		primary.add(templates.getInstanceOf("primaryLabel")
				.add("name", "string")
				.add("tag", TypeSymbol.STRING.getTag()).render());
		String primaryTokens = String.join("\n", primary);

		String bools = templates.getInstanceOf("bool_constants").add("label", TypeSymbol.BOOL.getTag()).render();

		String constantTokens = new String("");
		for (ST currConstant : constants) {
			constantTokens += currConstant.render();
			constantTokens += '\n';
		}
		String primitives = new String("");
		primitives += primaryTokens + "\n";
		primitives += bools + "\n";
		primitives += constantTokens + '\n';

		String classNameTokens = new String("");
		for (String currName : classNames) {
			classNameTokens += templates.getInstanceOf("sequence").add("e", currName).render();
			classNameTokens += '\n';

		}
		String classObjectTokens = new String("");
		for  (String currObj : classObjs) {
			classObjectTokens += currObj;
			classObjectTokens += '\n';
		}
		String prototypeObjectTokens = new String("");
		for (String currObj : objectPrototypes) {
			prototypeObjectTokens += currObj;
			prototypeObjectTokens += '\n';
		}
		String dispatchTableTokens = new String("");
		for (String currTable : dispatchTables) {
			dispatchTableTokens += currTable;
			dispatchTableTokens += '\n';
		}
		String objectTokens = new String("");
		objectTokens += classObjectTokens;
		objectTokens += "\n\n";
		objectTokens += prototypeObjectTokens;
		objectTokens += "\n\n";
		objectTokens += dispatchTableTokens;
		objectTokens += "\n\n";


		String methodTokens = new String("");
		for (String currMethod : methods) {
			methodTokens += currMethod;
			methodTokens += "\n\n";
		}

		return templates.getInstanceOf("program")
				.add("primitives", primitives)
				.add("classes", classNameTokens)
				.add("objects", objectTokens)
				.add("methods", methodTokens);
	}

	@Override
	public ST visit(ASTClassNode classNode) {
		scope = classNode.getType();

		methods.add(addObjectInit(classNode).render());

		for (ASTClassContentNode currField : classNode.getContent()) {
			if (currField instanceof ASTMethodNode) {
				var method = currField.accept(this);
				String methodName = new String(classNode.getType().getName());
				methodName += ".";
				methodName += ((ASTMethodNode) currField).getMethodSymbol().getName();
				method.add("name", methodName);
				methods.add(method.render());
			}
		}
		return null;
	}

	@Override
	public ST visit(ASTMethodNode methodNode) {
		scope = methodNode.getMethodSymbol();
		var methodST = templates.getInstanceOf("method");
		methodST.add("body", methodNode.getBody().accept(this));
		methodST.add("offset", methodNode.getParams().size() * 4 + 12);
		scope = scope.getParent();

		return methodST;
	}

	@Override
	public ST visit(ASTAttributeNode attributeNode) {
		var expr = attributeNode.getValue();
		if (expr == null) {
			return null;
		}

		return templates.getInstanceOf("storeAttribute")
				.add("val", expr.accept(this))
				.add("offset", attributeNode.getIdSymbol().getOffset());
	}

	@Override
	public ST visit(ASTLocalVarNode localVarNode) {
		var valueExpr= localVarNode.getValue();
		var varSymbol = localVarNode.getIdSymbol();
		var varType = varSymbol.getType();

		String value;
		if (valueExpr == null) {
			value = initPrimary(1, varType);
		} else {
			value = valueExpr.accept(this).render();
		}

		return templates.getInstanceOf("storeVar")
				.add("val", value)
				.add("offset", varSymbol.getOffset());
	}

	@Override
	public ST visit(ASTIntNode intNode) {
		String constInt = "int_const" +  addInt(Integer.parseInt(intNode.getSymbol()));
		return templates.getInstanceOf("literal")
				.add("addr", constInt);
	}

	@Override
	public ST visit(ASTIdNode idNode) {
		var idName = idNode.getSymbol();
		if (idName.equals("self")) {
			return templates.getInstanceOf("self");
		}

		var idSymbol = (IdSymbol)scope.lookup(idName);
		ST idST;

		if (idSymbol.isAttribute()) {
			idST = templates.getInstanceOf("attribute");
		} else {
			idST = templates.getInstanceOf("loadVar");
		}

		return idST.add("offset", idSymbol.getOffset());
	}

	@Override
	public ST visit(ASTBoolNode boolNode) {
		int boolVal;
		if (boolNode.getSymbol().equals("true")) {
			boolVal = 1;
		} else {
			boolVal = 0;
		}
		return templates.getInstanceOf("literal")
				.add("addr", "bool_const" + boolVal);
	}

	@Override
	public ST visit(ASTStringNode stringNode) {
		String constStr = "str_const" + addString(stringNode.getSymbol());
		return templates.getInstanceOf("literal")
				.add("addr", constStr);
	}

	@Override
	public ST visit(ASTAssignNode assignNode) {
		ST value = assignNode.getValue().accept(this);
		var idSymbol = (IdSymbol)scope.lookup(assignNode.getId().getText());
		ST assignST;

		if (idSymbol.isAttribute()) {
			assignST = templates.getInstanceOf("storeAttribute");
		} else {
			assignST = templates.getInstanceOf("storeVar");
		}
		assignST.add("val", value);
		assignST.add("offset", idSymbol.getOffset());
		return assignST;
	}

	@Override
	public ST visit(ASTNewNode newNode) {
		var type = newNode.getType().getText();
		ST newInstance;
		if (type.equals("SELF_TYPE")) {
			newInstance = templates.getInstanceOf("newSelfType");
		} else {
			newInstance = templates.getInstanceOf("new");
			newInstance.add("type", type);
		}

		return newInstance;
	}

	@Override
	public ST visit(ASTDispatchNode dispatchNode) {
		var method = dispatchNode.getCallee().getText();
		int offset = dispatchNode.getCallerType().lookupMethod(method).getOffset();

		List <ASTExpressionNode> reversedParams = dispatchNode.getParams();
		for (int i = 0, j = reversedParams.size() - 1; i < j; i++) {
			reversedParams.add(i, reversedParams.remove(j));
		}

		String params = "";

		for(ASTExpressionNode parameterExpression : reversedParams) {
			var param = parameterExpression.accept(this);
			params += templates.getInstanceOf("dispatchParam")
					.add("param", param).render();
			params += '\n';
		}

		var exprCaller = dispatchNode.getCaller();

		ST caller = null;
		ST dispatch;
		if (exprCaller != null && !exprCaller.getSymbol().equals("self")) {
			caller = exprCaller.accept(this);
		}
		if (caller != null) {
			dispatch = templates.getInstanceOf("dispatchStatic");
			dispatch.add("method", method);
			dispatch.add("idx", labelCounter++);
			dispatch.add("params", params);
			dispatch.add("offset", offset);
			dispatch.add("path", getPath(dispatchNode));
			dispatch.add("line", dispatchNode.getCallee().getLine());
			dispatch.add("explicit", caller);
		} else {
			dispatch = templates.getInstanceOf("dispatchDynamic");
			dispatch.add("method", method);
			dispatch.add("idx", labelCounter++);
			dispatch.add("params", params);
			dispatch.add("offset", offset);
			dispatch.add("path", getPath(dispatchNode));
			dispatch.add("line", dispatchNode.getCallee().getLine());
		}
		String callerLoad  = new String("");
		var specificCaller = dispatchNode.getActualCaller();
		if (specificCaller != null) {
			callerLoad  += templates.getInstanceOf("specificCallerDispatch").add("specific", specificCaller.getText()).render();
		} else {
			callerLoad += templates.getInstanceOf("generalCallerDispatch").render();
		}
		dispatch.add("specific", callerLoad);

		return dispatch;
	}

	@Override
	public ST visit(ASTBinaryOperatorNode binaryOpNode) {

		if (operators.get("arithmetic").containsKey(binaryOpNode.getSymbol())) {
			ST arithmeticST = templates.getInstanceOf("arithm");
			arithmeticST.add("leftOp", binaryOpNode.getLeftOp().accept(this))
					.add("rightOp", binaryOpNode.getRightOp().accept(this));
			arithmeticST.add("op", operators.get("arithmetic").get(binaryOpNode.getSymbol()));
			return arithmeticST;
		} else if (operators.get("comparison").containsKey(binaryOpNode.getSymbol())) {
			ST compST = templates.getInstanceOf("cmp")
					.add("leftOp", binaryOpNode.getLeftOp().accept(this))
					.add("rightOp", binaryOpNode.getRightOp().accept(this));
			compST.add("op", operators.get("comparison").get(binaryOpNode.getSymbol()));
			compST.add("labelCounter", labelCounter++);
			return compST;

		}
		return templates.getInstanceOf("equal")
				.add("leftOp", binaryOpNode.getLeftOp().accept(this))
				.add("rightOp", binaryOpNode.getRightOp().accept(this))
				.add("labelCounter", labelCounter++);
	}

	@Override
	public ST visit(ASTUnaryOperatorNode unaryOpNode) {

		if (!unaryOpNode.getSymbol().equals("~")) {
			return templates.getInstanceOf(unaryOpNode.getSymbol())
					.add("expr", unaryOpNode.getOp().accept(this))
					.add("labelCounter", labelCounter++);
		}
		return templates.getInstanceOf("neg")
				.add("expr", unaryOpNode.getOp().accept(this));
	}

	@Override
	public ST visit(ASTIfNode ifNode) {
		return templates.getInstanceOf("if")
				.add("cond", ifNode.getCond().accept(this))
				.add("then", ifNode.getThenBranch().accept(this))
				.add("els", ifNode.getElseBranch().accept(this))
				.add("labelCounter", labelCounter++);
	}

	@Override
	public ST visit(ASTWhileNode whileNode) {
		return templates.getInstanceOf("while")
				.add("cond", whileNode.getCond().accept(this))
				.add("body", whileNode.getBody().accept(this))
				.add("labelCounter", labelCounter++);
	}

	@Override
	public ST visit(ASTLetNode letNode) {
		int variableOffset = -4 * letNode.getLocals().size();
		ST initST = templates.getInstanceOf("initLet")
				.add("space", variableOffset);
		List<String> letTokens = new ArrayList<>();
		letTokens.add(initST.render());

		int i = 1;

		scope = letNode.getLetSymbol();
		for (var local : letNode.getLocals()) {
			local.getIdSymbol().setOffset(-4 * i);
			letTokens.add(visit(local).render());
			i++;
		}

		letTokens.add(letNode.getBody().accept(this).render());
		scope = scope.getParent();

		ST finST = templates.getInstanceOf("initLet")
				.add("space",  4 * letNode.getLocals().size());
		letTokens.add(finST.render());
		ST returnVal = templates.getInstanceOf("sequence");
		returnVal.add("e", String.join("\n", letTokens));

		return returnVal;
	}

	@Override
	public ST visit(ASTCaseBranchNode caseBranchNode) {
		scope = caseBranchNode.getScope();
		var type = caseBranchNode.getTypeSymbol();
		ST brST = templates.getInstanceOf("caseBranch")
				.add("expr", caseBranchNode.getBody().accept(this))
				.add("smallTag", type.getTag())
				.add("bigTag", type.getMaxTag())
				.add("esacCounter", esacCounter)
				.add("caseCounter", caseCounter++);
		scope = scope.getParent();

		return brST;
	}

	@Override
	public ST visit(ASTCaseNode caseNode) {
		var branches = caseNode.getBranches();
		Collections.sort(branches, tagComparator);
		String branchTags = new String("");
		for (ASTCaseBranchNode branch : branches) {
			branchTags += this.visit(branch).render();
			branchTags += "\n";
		}

		return templates.getInstanceOf("case")
					.add("expr", caseNode.getVar().accept(this))
					.add("branches", branchTags)
					.add("labelCounter", esacCounter++)
					.add("path", getPath(caseNode))
					.add("line", caseNode.getToken().getLine());
	}

	@Override
	public ST visit(ASTFormalNode formalNode) {
		return null;
	}

	private String getPath(ASTNode node) {
		var ctx = node.getContext();
		while (!(ctx.getParent() instanceof CoolParser.ProgramContext)) {
			ctx = ctx.getParent();
		}
		String constStr = "str_const" + addString(new File(Compiler.fileNames.get(ctx)).getName());
		return constStr;
	}

	@Override
	public ST visit(ASTBlockNode blockNode) {
		ST blockST = templates.getInstanceOf("sequence");
		blockNode.getExpressions().forEach(expr -> blockST.add("e", expr.accept(this)));

		return blockST;
	}
}
