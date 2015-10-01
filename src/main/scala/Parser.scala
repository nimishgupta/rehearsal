package rehearsal

import scala.util.parsing.combinator._
import Syntax._

case class ParseError(msg: String) extends RuntimeException(msg)

private class Parser extends RegexParsers with PackratParsers {

	import Implicits._

	type P[T] = PackratParser[T]

	override protected val whiteSpace = """(\s|#.*|(/\*((\*[^/])|[^*])*\*/))+""".r

	// TODO(arjun): escape sequences? interpolation?
	lazy val stringVal: P[String] =
		"\"" ~> "[^\"]*".r <~ "\"" |
		"'" ~> "[^']*".r <~ "'"
	// TODO: is there a better way to write this?
	lazy val word: P[String] = "(::)?([a-zA-Z]+(::)?[a-zA-Z]+)+|[a-zA-Z]+".r ^^ { case x => x }
	lazy val id: P[String] = "" ~> "[a-z_][a-zA-Z0-9_]*".r
	lazy val attributeName: P[String] = "" ~> "[a-z]+".r
	lazy val dataType: P[String] = "" ~> "[A-Z][a-zA-Z]+".r
	lazy val varName: P[String] =  "$" ~> "[a-z_(::)][a-zA-Z0-9_(::)]*[a-zA-Z0-9_]+|[a-z_(::)]".r

	//Manifest
	lazy val manifest: P[Manifest] =
		edge |
		let |
		define |
		resource |
		classManifest |
		caseManifest |
		ite |
		exprMan

	lazy val body: P[Manifest] = "{" ~> prog <~ "}"

	lazy val classManifest: P[Manifest] =
		("class" ~> word) ~ opt(parameters) ~ opt("inherits" ~> word) ~ body ^^ {
			case x ~ Some(params) ~ y ~ m => Class(x, params, y, m)
			case x ~ None ~ y ~ m => Class(x, Seq(), y, m)
		}

	lazy val caseManifest: P[Manifest] = "case" ~ expr ~ "{" ~ cases ~ "}" ^^ {
		case _ ~ e ~ _ ~ lst ~ _ => MCase(e, lst)
	}

	lazy val cases: P[Seq[Case]] =
		"default" ~ ":" ~ body ^^ { case _ ~ _ ~ m => Seq(CaseDefault(m)) } |
		expr ~ ":" ~ body ~ cases ^^ { case e ~ _ ~ m ~ rest => CaseExpr(e, m) +: rest } |
		success(()) ^^ { case _ => Seq[Case]() }

	lazy val parameter: P[Argument] = opt(dataType) ~ varName ~ opt("=" ~> expr) ^^ {
		case typ ~ id ~ Some(default) => Argument(id, Some(default))
		case typ ~ id ~ None => Argument(id, None)
	}

	lazy val parameters: P[Seq[Argument]] = ("(" ~> repsep(parameter, ",") <~ opt(",")) <~ ")"

	lazy val resource: P[Manifest] = word ~ ("{" ~> rep1sep(resourcePair, ";") <~ "}") ^^ {
		case typ ~ Seq((id, attr)) => Resource(id, typ, attr)
		case typ ~ pairs => pairs.foldRight[Manifest](Empty) {
			case ((id, attr), m) => Block(Resource(id, typ, attr), m)
		}
	}

	lazy val resourcePair: P[(Expr, Seq[Attribute])] = (expr <~ ":") ~ attributes ^^ {
		case id ~ attr => (id, attr)
	}

	lazy val ite: P[Manifest] = "if" ~> expr ~ body ~ rep(elsif) ~ opt("else" ~> body) ^^ {
		case pred ~ thn ~ elsifs ~ els => ITE(pred, thn, elsifs.foldRight(els.getOrElse(Empty)) {
			case ((pred, body), acc) => ITE(pred, body, acc)
		})
	}

	lazy val elsif: P[(Expr, Manifest)] = "elsif" ~> expr ~ body ^^ { case pred ~ body => (pred, body) }

	lazy val edge: P[Manifest] =
		manifest ~ ("->" ~> manifest) ^^ { case parent ~ child => Edge(parent, child) } |
		manifest ~ ("<-" ~> manifest) ^^ { case child ~ parent => Edge(parent, child) }

	lazy val define: P[Manifest] = "define" ~> word ~ opt(parameters) ~ body ^^ {
		case name ~ Some(args) ~ body => Define(name, args, body)
		case name ~ None ~ body => Define(name, Seq(), body)
	}

	lazy val let: P[Manifest] = varName ~ ("=" ~> expr) ~ opt(manifest) ^^
								{ case id ~ e ~ body => Let(id, e, body.getOrElse(Empty)) }

	//Attribute
	lazy val attrId: P[Str] = id ^^ { case s => Str(s) }
	lazy val attribute: P[Attribute] =
		(attrId | vari) ~ ("=>" ~> (expr | attrId)) ^^ { case name ~ value => Attribute(name, value) }

	lazy val attributes: P[Seq[Attribute]] = repsep(attribute, ",") <~ opt(",")

	//Expr
	lazy val exprMan: P[Manifest] = expr ^^ { case e => E(e) }

	lazy val res: P[Expr] = word ~ ("[" ~> expr <~ "]") ^^ { case typ ~ e => Res(typ, e) }

	lazy val vari: P[Expr] = varName ^^ (Var(_))

  //
	// Expressions. Use "expr" to parse an expression. Do not use any of the other
	// parsers (e.g., atom, bop, etc.) outside this block.
	//

	lazy val atom: P[Expr] =
		bool |
		res |
		vari |
		string |
		"[" ~> repsep(expr, ",") <~ "]" ^^ { case es => Array(es) } |
		word ~ "(" ~ repsep(expr, ",") ~ ")" ^^ { case f ~ _ ~ xs ~ _  => App(f, xs) } |
		"(" ~ expr ~ ")" ^^ { case _ ~ e ~ _ => e }

	lazy val not: P[Expr] =
		"!" ~> not ^^ { Not(_) } |
		atom

	lazy val and: P[Expr] =
		and ~ ("and" ~> not) ^^ { case lhs ~ rhs => And(lhs, rhs) } |
		not

	lazy val or: P[Expr] =
		or ~ ("or" ~> and) ^^ { case lhs ~ rhs => Or(lhs, rhs) } |
		and

	lazy val bop: P[Expr] =
		bop ~ ("==" ~> or) ^^ { case lhs ~ rhs => Eq(lhs, rhs) } |
		bop ~ ("!=" ~> or) ^^ { case lhs ~ rhs => Not(Eq(lhs, rhs)) } |
		bop ~ ("=~" ~> or) ^^ { case lhs ~ rhs => Match(lhs, rhs) } |
		bop ~ ("!~" ~> or) ^^ { case lhs ~ rhs => Not(Match(lhs, rhs)) } |
		bop ~ ("in" ~> or) ^^ { case lhs ~ rhs => In(lhs, rhs) } |
		or

	lazy val expr: P[Expr] = bop

	//Constants
	lazy val bool: P[Expr] = "true" ^^ { _ => Bool(true) } |
				 "false" ^^ { _ => Bool(false) }

	lazy val string: P[Str] = stringVal ^^ (Str(_))

	def simplifyAttributes(lst: Seq[Attribute], src: Res): (Seq[Attribute], Manifest) = {
		lst.foldRight[(Seq[Attribute], Manifest)]((Seq(), Empty)) {
			case (Attribute(Str("before"), res), (attrs, m)) => (attrs, Block(Edge(E(src), E(res)), m))
			case (Attribute(Str("require"), res), (attrs, m)) => (attrs, Block(Edge(E(res), E(src)), m))
			case (attr, (attrs, m)) => (attr +: attrs, m)
		}
	}

	def desugar(m: Manifest): Manifest = m match {
		case Empty => Empty
		case Block(e1, e2) => Block(desugar(e1), desugar(e2))
		case Resource(Str(id), typ, attrs) => simplifyAttributes(attrs, Res(typ.capitalize, Str(id))) match {
			case (attrs, Empty) => Resource(Str(id), typ, attrs)
			case (attrs, m) => Block(Resource(Str(id), typ, attrs), m)
		}
		// TODO(arjun): Inheritance!
		case Class(x, params, _, m) => Define(x, params, desugar(m))
		case Resource(id, typ, attrs) => simplifyAttributes(attrs, Res(typ.capitalize, id)) match {
			case (attrs, Empty) => Resource(id, typ, attrs)
			case (attrs, m) => Block(Resource(id, typ, attrs), m)
		}
		case Let(id, value, body) => Let(id, value, desugar(body))
		case Define(name, args, body) => Define(name, args, desugar(body))
		case ITE(pred, thn, els) => ITE(pred, desugar(thn), desugar(els))
		case E(_) => m
		case Edge(m1, m2) => Edge(desugar(m1), desugar(m2))
		case MCase(e, lst) => {
			// TODO(arjun): should we ensure that "e" is only evaluted once? Does it
			// matter? (No side-effects, right?)
			lst.foldRight[Manifest](Empty) {
				// TODO(arjun): Eq(e, v) is not quite right. It won't work for
				// values that reduce to regular expressions. See this:
				// https://docs.puppetlabs.com/puppet/latest/reference/lang_conditional.html#case-matching
				case (CaseExpr(v, m), rest) => ITE(Eq(e, v), m, rest)
				case (CaseDefault(m), Empty) => m
				case (CaseDefault(_), _) => throw new Exception("default is not the last case (should have been caught by the grammar)")
			}
		}
	}

	//Program
	lazy val prog: P[Manifest] = rep(manifest) ^^ { case exprs => blockExprs(exprs) }

	def blockExprs(exprs: Seq[Manifest]): Manifest = {
		exprs.foldRight[Manifest](Empty) { case (m1, m2) => m1 >> m2 }
	}
	def parseString[A](expr: String, parser: Parser[A]): A = {
		parseAll(parser, expr) match{
			case Success(r, _) => r
			case m => throw new ParseError(s"$m")
		}
	}
}

object Parser {
	private val parser = new Parser()

	def parseBool(str: String): Expr = parser.parseString(str, parser.bool)
	def parseStr(str: String): Str = parser.parseString(str, parser.string)
	def parseOps(str: String): Expr = parser.parseString(str, parser.bop)
	def parseExpr(str: String): Expr = parser.parseString(str, parser.expr)
	def parseAttribute(str: String): Attribute = parser.parseString(str, parser.attribute)
	def parseArgument(str: String): Argument = parser.parseString(str, parser.parameter)
	def parseManifest(str: String): Manifest = parser.parseString(str, parser.manifest)
	def parse(str: String): Manifest = parser.desugar(parser.parseString(str, parser.prog))

	def parseFile(filename: String): Manifest = {
		import java.nio.file._
		parse(new String(Files.readAllBytes(Paths.get(filename))))
	}


}
