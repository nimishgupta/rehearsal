package parser

import Syntax._
import scalax.collection.mutable.Graph
import scalax.collection.mutable.Graph._
import scalax.collection.GraphEdge._

object Evaluator {
	//pipeline: toGraph(Graph(), eval(expandAll(parse(m))))

	case class EvalError(msg: String) extends RuntimeException(msg)
	case class GraphError(msg: String) extends RuntimeException(msg)
	type ManifestGraph = Graph[Manifest, DiEdge]

	def subExpr(varName: String, e: Expr, body: Expr): Expr = body match {
		case Str(_) => body
		case Res(typ, expr) => Res(typ, subExpr(varName, e, expr))
		case Var(id) if varName == id => e
		case Var(_) => body
		case Bool(_) => body
		case Not(expr) => Not(subExpr(varName, e, expr))
		case And(expr1, expr2) => And(subExpr(varName, e, expr1), subExpr(varName, e, expr2))
		case Or(expr1, expr2) => Or(subExpr(varName, e, expr1), subExpr(varName, e, expr2))
		case Eq(expr1, expr2) => Eq(subExpr(varName, e, expr1), subExpr(varName, e, expr2))
		case Match(expr1, expr2) => Match(subExpr(varName, e, expr1), subExpr(varName, e, expr2))
		case In(expr1, expr2) => In(subExpr(varName, e, expr1), subExpr(varName, e, expr2))
	}

	def subAttr(varName: String, e: Expr, attr: Attribute): Attribute = attr match {
		case Attribute(name, value) => Attribute(subExpr(varName, e, name), subExpr(varName, e, value))
	}	

	def sub(varName: String, e: Expr, body: Manifest): Manifest = body match {
		case Empty => body
		case Block(m1, m2) => Block(sub(varName, e, m1), sub(varName, e, m2))
		case Resource(title, typ, attrs) => Resource(title, typ, attrs.map(attr => subAttr(varName, e, attr)))
		case ITE(pred, m1, m2) => ITE(subExpr(varName, e, pred), sub(varName, e, m1), sub(varName, e, m2))
		case Edge(m1, m2) => Edge(sub(varName, e, m1), sub(varName, e, m2))
		case Define(name, params, m) => Define(name, params, sub(varName, e, m))
		case Let(v, expr, b) => Let(v, subExpr(varName, e, expr), sub(varName, e, b))
		case E(expr) => E(subExpr(varName, e, expr))
	}	

	def evalAttr(a: Attribute): Attribute = a match {
		case Attribute(name, value) => Attribute(evalExpr(name), evalExpr(value))
	}

	def evalExpr(e: Expr): Expr = e match {
		case Res(typ, e) => Res(typ, evalExpr(e))
		case Var(_) => e
		case Str(_) => e
		case Bool(_) => e
		case Not(e) => evalExpr(e) match {
			case Bool(b) => Bool(!b)
			case _ => throw EvalError(s"Cannot evaluate: Invalid argument for Not: $e")
		}
		case And(e1, e2) => (evalExpr(e1), evalExpr(e2)) match {
			case (Bool(b1), Bool(b2)) => Bool(b1 && b2)
			case _ => throw EvalError(s"Cannot evaluate: Invalid argument(s) for And: $e1, $e2")
		}
		case Or(e1, e2) => (evalExpr(e1), evalExpr(e2)) match {
			case (Bool(b1), Bool(b2)) => Bool(b1 || b2)
			case _ => throw EvalError(s"Cannot evaluate: Invalid argument(s) for Or: $e1, $e2")
		}
		case Eq(e1, e2) => if(evalExpr(e1) == evalExpr(e2)) Bool(true) else Bool(false)
		case Match(Str(e1), Str(e2)) => {
			val pat = e2.r
			e1 match {
				case pat(_) => Bool(true)
				case _ => Bool(false)
			}
		}
		case Match(e1, e2) => throw EvalError(s"Cannot evaluate: Invalid argument(s) for Match: $e1, $e2")
		case In(Str(e1), Str(e2)) => if(e2.contains(e1)) Bool(true) else Bool(false)
		case In(e1, e2) => throw EvalError(s"Cannot evaluate: Invalid argument(s) for In: $e1, $e2")
	}
	
	def eval(m: Manifest): Manifest = m match {
		case Empty => Empty
		case Block(Empty, m2) => eval(m2)
		case Block(m1, Empty) => eval(m1)
		case Block(m1, m2) => Block(eval(m1), eval(m2))
		case Resource(title, typ, attrs) => Resource(title, typ, attrs.map(evalAttr))
		case ITE(pred, m1, m2) if(evalExpr(pred) == Bool(true))  => eval(m1)
		case ITE(pred, m1, m2) if(evalExpr(pred) == Bool(false)) => eval(m2)
		case ITE(pred, _, _) => throw EvalError(s"Cannot evaluate: Invalid Predicate for if: $pred")
		case Edge(m1, m2) => Edge(eval(m1), eval(m2))
		case Define(_, _, _) => m
		case Let(varName, e, body) => eval(sub(varName, e, body))
		case E(e) => E(evalExpr(e))
	}

	/*what to do if instance contains an attribute that doesn't have corresponding parameter in define? : 
	  		ignoring for now */
	def subArgs(params: Seq[Argument], args: Seq[Attribute], body: Manifest): Manifest = 
		(params, args) match {
			case (Seq(), _) => body
			case (Argument(paramName) :: paramsT, Attribute(Str(attrName), value) :: argsT) => {
				if(paramName == attrName) subArgs(paramsT, argsT, sub(paramName, value, body))
				else 											subArgs(params, argsT, body)
			}
			case (Argument(paramName) :: paramsT, Attribute(Var(attrName), value) :: argsT) => {
				if(paramName == attrName) subArgs(paramsT, argsT, sub(paramName, value, body))
				else 											subArgs(params, argsT, body)
			}
			case (_, Seq()) => throw EvalError(s"""Not enough attributes for 
				defined type instantiation: params = $params; body = $body""")
			case _ => throw EvalError(s"Unexpected attribute pattern: attrs = $args")
		}

	def expand(m: Manifest, d: Define): Manifest = (m, d) match {
		case (Empty, _) => Empty
		case (Block(m1, m2), _) => Block(expand(m1, d), expand(m2, d))
		case (Resource(_, typ, attrs), Define(name, params, body)) if name == typ => 
			subArgs(params, attrs, body)
		case (Resource(_, _, _), _) => m //do nothing
		case (ITE(pred, thn, els), _) => ITE(pred, expand(thn, d), expand(els, d))
		case (Edge(m1, m2), _) => Edge(expand(m1, d), expand(m2, d))
		case (Define(name1, _, _), Define(name2, _, _)) if name1 == name2 => Empty //remove define declaration
		case (Define(name, params, body), _) => Define(name, params, expand(body, d))
		case (Let(x, e, body), _) => Let(x, e, expand(body, d))
		case (E(Res(typ, e)), _) => m //do something?
		case (E(_), _) => m
	}

	def findDefine(m: Manifest): Option[Define] = m match {
		case d@Define(_, _, _) => Some(d)
		case Block(m1, m2) => {
			val m1res = findDefine(m1)
			if(m1res == None) findDefine(m2) else m1res
		}
		case Edge(m1, m2) => {
			val m1res = findDefine(m1)
			if(m1res == None) findDefine(m2) else m1res
		}
		case ITE(_, m1, m2) => {
			val m1res = findDefine(m1)
			if(m1res == None) findDefine(m2) else m1res
		}
		case Let(_, _, body) => findDefine(body)
		case Empty|E(_)|Resource(_, _, _) => None
	}

	def expandAll(m: Manifest): Manifest = {
		var d: Option[Define] = findDefine(m)
		var m2: Manifest = m
		while(d != None){
			m2 = expand(m2, d.get)
			d = findDefine(m2)
		}
		m2
	}

	def addEdges(g: ManifestGraph, e: Edge): ManifestGraph = e match {
		case Edge(Block(m11, m12), m2) => addEdges(g, Edge(m11, m2)) ++ addEdges(g, Edge(m12, m2))
		case Edge(m1, Block(m21, m22)) => addEdges(g, Edge(m1, m21)) ++ addEdges(g, Edge(m1, m22))
		case Edge(m1, m2) => g += DiEdge(m1, m2)
	}	

	def toGraph(m: Manifest): ManifestGraph = toGraphRec(Graph(), m)

	def toGraphRec(g: ManifestGraph, m: Manifest): ManifestGraph = m match {
		case Empty => g
		case Block(m1, m2) => toGraphRec(g, m1) ++ toGraphRec(g, m2)
		case e@Edge(_, _) => addEdges(g, e)
		case Resource(_, _, _) | E(Res(_, _)) | E(Str(_)) | E(Bool(_)) => g + m
		case ITE(_, _, _) | Let(_, _, _) | E(_) | Define(_, _, _) => 
			throw GraphError(s"m is not fully evaluated $m")
	}	
}