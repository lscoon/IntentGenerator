package path.analysis.unit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.javatuples.Pair;

import global.Globals;
import global.Database;
import global.Init;
import type.UnitPath;
import soot.Body;
import soot.BooleanType;
import soot.ByteType;
import soot.Local;
import soot.SootField;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.Constant;
import soot.jimple.DefinitionStmt;
import soot.jimple.FieldRef;
import soot.jimple.IfStmt;
import soot.jimple.ParameterRef;
import soot.jimple.StaticFieldRef;
import soot.jimple.StringConstant;
import soot.jimple.VirtualInvokeExpr;
import soot.jimple.internal.AbstractJimpleIntBinopExpr;
import soot.jimple.internal.JCastExpr;
import soot.jimple.internal.JVirtualInvokeExpr;
import soot.toolkits.scalar.SimpleLocalDefs;

public class ValueFind {
	
	protected final static Pair<Pair<Value,Unit>,Pair<Value,Unit>> findValuesOfByteType(SootMethod method, UnitPath currPath, SimpleLocalDefs methodDefs, Unit currUnit, Value value) {
		Pair<Value,Unit> leftVal = null;
		Pair<Value,Unit> rightVal = null;
		if(value instanceof Local) {
			Local local = (Local) value;
			if(local.getType() instanceof ByteType) {
				List<Unit> potentialCmpUnits = methodDefs.getDefsOfAt(local, currUnit);
				for(Unit potentialCmpUnit : potentialCmpUnits)
					if(StmtHandle.isDefInPathAndLatest(currPath, methodDefs, currUnit, local, potentialCmpUnit))
						if(potentialCmpUnit.toString().contains("cmp")) {
							Init.logger.trace("Found potential cmp* statement: " +potentialCmpUnit.toString());
							if(potentialCmpUnit instanceof DefinitionStmt) {
								DefinitionStmt defStmt = (DefinitionStmt) potentialCmpUnit;
								Value rightOp = defStmt.getRightOp();
								if(rightOp instanceof AbstractJimpleIntBinopExpr) {
									AbstractJimpleIntBinopExpr cmpExpr = (AbstractJimpleIntBinopExpr) rightOp;
									leftVal = findOriginalVal(method,currPath,methodDefs,potentialCmpUnit,cmpExpr.getOp1());
									rightVal = findOriginalVal(method,currPath,methodDefs,potentialCmpUnit,cmpExpr.getOp2());
								}
							}
						}
			}
		}
		return new Pair<Pair<Value,Unit>,Pair<Value,Unit>>(leftVal,rightVal);
	}
	
	public final static Pair<Pair<Value,Unit>,Pair<Value,Unit>> findValuesOfBoolType(SootMethod method, UnitPath currPath, SimpleLocalDefs methodDefs, Unit currUnit, Value value){
		Pair<Value,Unit> leftVal = null;
		Pair<Value,Unit> rightVal = null;
		if(value instanceof Local) {
			Local local = (Local) value;
			if(local.getType() instanceof BooleanType) {
				List<Unit> potentialStringEqualsUnits = methodDefs.getDefsOfAt(local,currUnit);
				for(Unit pseUnit: potentialStringEqualsUnits)
					if(StmtHandle.isDefInPathAndLatest(currPath, methodDefs, currUnit, local, pseUnit)) {
						Init.logger.trace("Found potential string equal comparison statement: " + pseUnit);
						if(pseUnit instanceof DefinitionStmt) {
							DefinitionStmt defStmt = (DefinitionStmt) pseUnit;
							if(defStmt.getRightOp() instanceof JVirtualInvokeExpr) {
								JVirtualInvokeExpr jviExpr = (JVirtualInvokeExpr) defStmt.getRightOp();
								if (jviExpr.getMethod().getName().equals("equals") && jviExpr.getMethod().getDeclaringClass().getName().equals("java.lang.String")) {
									Init.logger.debug("Identified actual string equals comparison statement");
									leftVal = findOriginalVal(method,currPath,methodDefs,pseUnit,jviExpr.getBase());
									rightVal = findOriginalVal(method,currPath,methodDefs,pseUnit,jviExpr.getArg(0));
								}
								if (Pattern.matches("hasExtra",jviExpr.getMethod().getName())) {
									Init.logger.debug("Found hasExtra invocation");
									leftVal = findOriginalVal(method,currPath,methodDefs,pseUnit,jviExpr.getBase());
									rightVal = findOriginalVal(method,currPath,methodDefs,pseUnit,jviExpr.getArg(0));

									Body b = method.getActiveBody();
									List<Unit> currPathList = new ArrayList<Unit>(currPath.path);
									int indexOfUnit = currPathList.indexOf(currUnit);
									if (indexOfUnit == -1) 
										throw new RuntimeException(currUnit + " is not in path");
									Unit succ = currPathList.get(indexOfUnit+1);

									boolean isFallThrough = isFallThrough(b, currUnit, succ);
									String newAssert = null;
									if(isFallThrough) // intent contains the extra
										newAssert = "(assert (exists ((index Int)) (= (select keys index) " + rightVal.getValue0().toString() + ")))";
									else newAssert = "(assert (forall ((index Int)) (not(= (select keys index) " + rightVal.getValue0().toString() + "))))";
									currPath.conds.add(newAssert);
									leftVal = new Pair<Value,Unit>(jviExpr.getBase(),leftVal.getValue1());
								}
							}
						}
					}
			}
		}
		return new Pair<Pair<Value,Unit>,Pair<Value,Unit>>(leftVal,rightVal);
	}

	private final static Pair<Value,Unit> findOriginalVal(SootMethod method, UnitPath currPath, SimpleLocalDefs methodDefs, Unit potentialCmpUnit, Value cmpOp) {
		Value originVal = null;
		Unit defUnit = null;
		if(cmpOp instanceof Local) {
			Value cmpVal = cmpOp;
			Pair<Value,Unit> pair = findOriginValFromCmpVal(method,currPath,methodDefs,potentialCmpUnit,cmpVal);
			originVal = pair.getValue0();
			defUnit = pair.getValue1();
		}
		else if(cmpOp instanceof Constant) {
			originVal = cmpOp;
		}
		else throw new RuntimeException("Unhandled cmpOp for:" + potentialCmpUnit);
		return new Pair<Value,Unit>(originVal,defUnit);
	}
	
	private final static Pair<Value,Unit> findOriginValFromCmpVal(SootMethod method, UnitPath currPath, SimpleLocalDefs methodDefs, Unit potentialCmpUnit, Value cmpVal) {
		Value originVal = null;
		Unit defUnit = null;
		String key = null;
		
		Local cmpLocal = (Local) cmpVal;
		List<Unit> castOrInvokeUnits = methodDefs.getDefsOfAt(cmpLocal, potentialCmpUnit);
		for(Unit coiUnit : castOrInvokeUnits)
			if(StmtHandle.isDefInPathAndLatest(currPath, methodDefs, potentialCmpUnit, cmpLocal, coiUnit)) {
				Init.logger.trace("Foune potential cast or invoke stmt: " + coiUnit);
				if(coiUnit instanceof DefinitionStmt) {
					DefinitionStmt coiStmt = (DefinitionStmt) coiUnit;
					originVal = coiStmt.getLeftOp();
					defUnit = coiUnit;
					if(!currPath.path.contains(defUnit))
						continue;
					
					if(coiStmt.getRightOp() instanceof JCastExpr) {
						Init.logger.trace("Handling cast expression from potential API invocation");
						JCastExpr expr = (JCastExpr) coiStmt.getRightOp();
						if(expr.getOp() instanceof Local) {
							Local localFromCast = (Local) expr.getOp();
							List<Unit> defsOfLocalFromCast = methodDefs.getDefsOfAt(localFromCast, coiUnit);
							for(Unit defLocalAssignFromCastUnit : defsOfLocalFromCast)
								if(StmtHandle.isDefInPathAndLatest(currPath, methodDefs, coiUnit, localFromCast, defLocalAssignFromCastUnit))
									if(defLocalAssignFromCastUnit instanceof DefinitionStmt) {
										DefinitionStmt defLocalAssignFromCastStmt = (DefinitionStmt) defLocalAssignFromCastUnit;
										originVal = defLocalAssignFromCastStmt.getLeftOp();
										defUnit = defLocalAssignFromCastUnit;
										key = extractKeyFromIntentExtra(defLocalAssignFromCastStmt,methodDefs,currPath);
									}
						}
					}
					else key = extractKeyFromIntentExtra(coiStmt,methodDefs,currPath);
					
					if(coiStmt.getRightOp() instanceof StringConstant) {
						Local local = (Local) coiStmt.getLeftOp();
						String symbol = SymbolGenerate.createSymbol(local, method, coiStmt);
						Database.symbolLocalMap.put(symbol, local);
						StringConstant stringConst = (StringConstant)coiStmt.getRightOp();
						currPath.conds.add("(assert (= " + symbol + " " + stringConst + " ))");
						currPath.decls.add("(declare-const " + symbol + " String )");
					}
					
					if(coiStmt.getRightOp() instanceof ParameterRef) {
						Init.logger.trace("Found parameter ref when searching for original value");
						if(coiStmt.getLeftOp() instanceof Local) {
							Local prLocal = (Local) coiStmt.getLeftOp();
							String localSymbol = SymbolGenerate.createSymbol(prLocal, method, coiStmt);
							originVal = coiStmt.getLeftOp();
							ParameterRef pr = (ParameterRef) coiStmt.getRightOp();
							String prSymbol = SymbolGenerate.createParamRefSymbol(prLocal, pr.getIndex(), method, coiStmt);
							currPath.decls.add("(declare-const " + prSymbol + " ParamRef)");
							currPath.conds.add("(assert ( = (index "+prSymbol+") "+pr.getIndex()+"))\n"+
									"(assert ( = (type "+prSymbol+") \""+pr.getType()+"\"))\n"+
									"(assert ( = (method "+prSymbol+") \""+method.getDeclaringClass().getName()+"."+method.getName()+"\"))\n"+
									"(assert (= (hasParamRef "+localSymbol+") "+prSymbol+"))");
							defUnit = coiStmt;
						}
					}
				}
			}	
		Database.valueKeyMap.put(originVal,key);
		return new Pair<Value,Unit>(originVal,defUnit);
	}
	
	private static boolean isFallThrough(Body body, Unit inUnit, Unit succ) {
		if(succ == null) {
			 if(inUnit instanceof IfStmt)
				 return true;
			 else return false;
		}
		if(!inUnit.fallsThrough())
			return false;
		return body.getUnits().getSuccOf(inUnit) == succ;
	}
	
	private static String extractKeyFromIntentExtra(DefinitionStmt defStmt, SimpleLocalDefs methodDefs, UnitPath currPath) {
		String key = null;
		if (defStmt.getRightOp() instanceof JVirtualInvokeExpr) {
			JVirtualInvokeExpr expr = (JVirtualInvokeExpr) defStmt.getRightOp();
			boolean keyExtractionEnabled = false;
			if (Pattern.matches("get.*Extra",expr.getMethod().getName()))
				if (expr.getMethod().getDeclaringClass().toString().equals("android.content.Intent"))
					keyExtractionEnabled = true;
			if (Pattern.matches("has.*Extra",expr.getMethod().getName()))
				if (expr.getMethod().getDeclaringClass().toString().equals("android.content.Intent"))
					keyExtractionEnabled = true;
			if (Globals.bundleExtraDataMethodsSet.contains(expr.getMethod().getName() )) {
				if (expr.getMethod().getDeclaringClass().getName().equals("android.os.Bundle"))
					keyExtractionEnabled = true;
				if (expr.getMethod().getDeclaringClass().getName().equals("android.os.BaseBundle"))
					keyExtractionEnabled = true;
			}
			
			if (keyExtractionEnabled) {
				Init.logger.debug("We can extract the key from this expression");
				if (!(expr.getArg(0) instanceof StringConstant)) {
					if (expr.getArg(0) instanceof Local) {
						Local keyLocal = (Local)expr.getArg(0);
						List<Unit> defUnits = methodDefs.getDefsOfAt(keyLocal,defStmt);
						for (Unit defUnit : defUnits) {
							if (!StmtHandle.isDefInPathAndLatest(currPath,methodDefs,defStmt,keyLocal,defUnit))
								continue;
							if (defUnit instanceof DefinitionStmt) {
								DefinitionStmt keyLocalDefStmt = (DefinitionStmt)defUnit;
								if (keyLocalDefStmt.getRightOp() instanceof VirtualInvokeExpr) {
									VirtualInvokeExpr invokeExpr = (VirtualInvokeExpr)keyLocalDefStmt.getRightOp();
									if (invokeExpr.getBase() instanceof Local)
										if (invokeExpr.getMethod().getDeclaringClass().getType().toString().equals("java.lang.Enum")) {
											Local base = (Local) invokeExpr.getBase();
											List<Unit> baseDefs = methodDefs.getDefsOfAt(base, keyLocalDefStmt);
											for (Unit baseDef : baseDefs) {
												if (!StmtHandle.isDefInPathAndLatest(currPath,methodDefs,keyLocalDefStmt,base,baseDef))
													continue;
												if (baseDef instanceof DefinitionStmt) {
													DefinitionStmt baseDefStmt = (DefinitionStmt)baseDef;
													if (baseDefStmt.getRightOp() instanceof FieldRef) {
														FieldRef fieldRef = (FieldRef)baseDefStmt.getRightOp();
														if ( fieldRef.getField().getDeclaringClass().toString().equals(invokeExpr.getBase().getType().toString()) )
															key = fieldRef.getField().getName();
													}
												}
											}
										
									}
									continue;
								} else if (keyLocalDefStmt.getRightOp() instanceof StaticFieldRef) {
									SootField keyField = ((StaticFieldRef) keyLocalDefStmt.getRightOp()).getField();
									SootMethod clinitMethod = keyField.getDeclaringClass().getMethodByName("<clinit>");
									if (clinitMethod.hasActiveBody()) {
										Body clinitBody = clinitMethod.getActiveBody();
										for (Unit clinitUnit : clinitBody.getUnits())
											if (clinitUnit instanceof DefinitionStmt) {
												DefinitionStmt clinitDefStmt = (DefinitionStmt) clinitUnit;
												if (clinitDefStmt.getLeftOp() instanceof StaticFieldRef) {
													SootField clinitField = ((StaticFieldRef) clinitDefStmt.getLeftOp()).getField();
													if (clinitField.equals(keyField))
														if (clinitDefStmt.getRightOp() instanceof StringConstant) {
															StringConstant clinitStringConst = (StringConstant) clinitDefStmt.getRightOp();
															key = clinitStringConst.value;
														}
												}
											}
									}
								} else throw new RuntimeException("Unhandled case for: " + keyLocalDefStmt.getRightOp());
							}
						}
					}
				} else key = expr.getArg(0).toString();
			}
		}
		return key;
	}
}
