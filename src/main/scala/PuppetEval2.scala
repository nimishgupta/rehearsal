package rehearsal

object PuppetEval2 {

  import PuppetSyntax2._
  import scalax.collection.Graph
  import scalax.collection.Graph._
  import scalax.collection.GraphPredef._
  import scalax.collection.GraphEdge._
  import scalax.collection.edge.Implicits._
  import Implicits._
  import scala.util.parsing.combinator._

  object StringInterpolator {
    val parser = new PuppetParser2()
    import parser._

    def interpolate(env: Env, str: String): Expr = {
      val strs = str.split("""\$""")
      Str(strs(0) + strs.toList.drop(1).map(helper(env)).mkString(""))
    }

    def helper(env: Env)(str: String): String = {
      val tokens = str.split("""\s+""").toList
      checkBraces(env, tokens(0)) + tokens.drop(1).mkString("")
    }

    def checkBraces(env: Env, str: String): String = {
      str.indexOf("{") match {
        case 0 => {
          val ix = str.indexOf("}")
          val expr = str.substring(1, ix)
          val rest = str.substring(ix+1, str.length)
          evaluate(env, expr) + rest
        }
        case _ => evaluate(env, str)
      }
    }

    def evaluate(env: Env, str: String): String = {
      val strPrime = if(str.charAt(0) != '$') "$" + str else str
      parseAll(expr, strPrime) match {
        case Success(expr, _) => evalExpr(env, expr) match {
          case Str(s) => s
          case _ => throw EvalError(s"None string expression evaluated during string interpolation: $expr")
        }
        case m => throw EvalError(s"Could not parse interpolated expression: $m")
      }
    }

  }

  val primTypes =  Set("file", "package", "user", "group", "service",
                       "ssh_authorized_key", "augeas", "notify")

  case class VClass(name: String, env: Env,
                    args: Map[String, Option[Expr]], body: Manifest)

  case class VDefinedType(
    name: String,
    env: Env,
    args: Map[String, Option[Expr]],
    body: Manifest)

  case class Env(scope: Map[String, Expr], enclosing: Option[Env]) {

    // NOTE(arjun): Do not change this. Strictly speaking, this is not an
    // error. But, silently returning undef will make us miss other bugs in
    // our implementation. If a test manifest actually uses this feature,
    // modify it so that the undeclared variable is set to Undef.
    def getOrError(x: String): Expr = {
      scope.getOrElse(x, enclosing match {
        case None => throw EvalError(s"undefined identifier $x")
        case Some(env) => env.getOrError(x)
      })
    }

    def newScope(): Env = Env(Map(), Some(this))

    def default(x: String, v: Expr) = scope.get(x) match {
      case None => Env(scope + (x -> v), enclosing)
      case Some(_) => this
    }

    def +(xv: (String, Expr)): Env = {
      val (x, v) = xv
      if (scope.contains(x)) {
        throw EvalError(s"identifier already set: $x")
      }
      else {
        Env(scope + (x -> v), enclosing)
      }
    }

    def forceSet(x: String, v: Expr) = new Env(scope + (x -> v), enclosing)

    def ++(other: Map[String, Expr]): Env = {
      Env(scope ++ other, enclosing)
    }

  }

  object Env {

    val empty = Env(Map(), None)

  }


  case class State(resources: Map[Node, ResourceVal],
                   deps: Graph[Node, DiEdge],
                   env: Env,
                   definedTypes: Map[String, VDefinedType],
                   classes: Map[String, VClass],
                   classGraph: Map[String, Graph[Node, DiEdge]],
                   stages: Map[String, Set[Node]],
                   aliases: Map[Node, Node])

  def evalAttrs(env: Env, attrs: Seq[Attribute]): Map[String, Expr] = {
    // TODO(arjun): duplicates are probably an error
    attrs.map({
      case Attribute(kExpr, v) => evalExpr(env, kExpr) match {
        case Str(k) => k -> evalExpr(env, v)
        case _ => throw EvalError("attribute key should be a string")
      }
    }).toMap
  }

  def evalTitle(env: Env, titleExpr: Expr): String = {
    evalExpr(env, titleExpr) match {
      case Str(title) => title
      case v => throw EvalError(s"title should be a string, but got $v ${titleExpr.pos}")
    }
  }

  def resourceRefs(e: Expr): Seq[Node] = e match {
    case EResourceRef(typ, Str(title)) => Seq(Node(typ, title))
    case Array(seq) => seq.map(resourceRefs).flatten
    case _ => throw EvalError(s"expected resource reference, got $e ${e.pos}")
  }

  def extractRelationships(s: Node,
                           attrs: Map[String, Expr]): Graph[Node, DiEdge] = {

    def get(key: String) = resourceRefs(attrs.getOrElse(key, Array(Seq())))
    val before = get("before").map(r => DiEdge(s, r))
    val require = get("require").map(r => DiEdge(r, s))
    val notify = get("notify").map(r => DiEdge(s, r))
    val subscribe = get("subscribe").map(r => DiEdge(r, s))
    Graph.from(edges = Seq(before, require, notify, subscribe).flatten)
  }

  val metaparameters = Seq("before", "require", "notify", "subscribe", "alias")

  // Produces a new state and a list of resource titles
  // TODO(arjun): Handle "require" and "before" dependencies.
  def evalResource(st: State, resource: Resource): (State, List[Node]) = {
    resource match {
      case ResourceRef(typ, titleExpr, Seq()) => {
        val node = Node(typ.toLowerCase, evalTitle(st.env, titleExpr))
        (st.copy(deps = st.deps + node), List(node))
      }
      case ResourceDecl(typ, lst) => {
        val (vals, relationships, aliases) = lst.map({ case (titleExpr, attrs) =>
          val attrVals = evalAttrs(st.env, attrs)
          val resource = ResourceVal(typ, evalTitle(st.env, titleExpr),
                                     attrVals -- metaparameters)
          val relationships = extractRelationships(resource.node, attrVals)
          val aliases = attrVals.get("alias") match {
            case Some(v) => {
              val alias = v.value[String].getOrElse(throw EvalError("alias must be a string"))
              Map(Node(typ, alias) -> resource.node)
            }
            case None => Map[Node, Node]()
          }
          (resource, relationships, aliases)
        }).unzip3
        val aliasMap = aliases.reduce(_ ++ _)

        val newNodes: Set[Node] = vals.map(_.node).toSet
        //println(s"Creating nodes $nodes")
        val redefinedResources = st.resources.keySet.intersect(newNodes)
        if (redefinedResources.isEmpty == false) {
          throw EvalError(s"${redefinedResources.head} is already defined")
        }
        else {
          val newResources = vals.map(r => r.node -> r).toMap
          (st.copy(resources = st.resources ++ newResources,
                   deps = st.deps ++
                     Graph[Node, DiEdge](aliasMap.keys.toSeq: _*) ++
                     Graph.from(newNodes, edges = Set()) ++
                     relationships.reduce(_ union _),
                   stages = newResources.foldRight(st.stages)(updateStage),
                   aliases = st.aliases ++ aliasMap),
           newNodes.toList)
        }
      }
    }
  }

  def evalEdges(st: State, lst: Seq[Resource]): (State, List[Node]) = lst match {
    case Seq() => (st, Nil)
    case r :: rest => {
      val (st1, titlesHd) = evalResource(st, r)
      val (st2, titlesTl) = evalEdges(st1, rest)
      val newDeps = for (x <- titlesHd; y <- titlesTl) yield { DiEdge(x, y) }
      (st2.copy(deps = st2.deps ++ newDeps), titlesHd)
    }
  }

  def matchCase(env: Env, v: Expr, aCase: Case): Boolean = aCase match {
    case CaseDefault(_) => true
    case CaseExpr(e, _) => evalExpr(env, e) == v
  }

  def evalManifest(st: State, manifest: Manifest): State = manifest match {
    case Empty => st
    case Block(m1, m2) => evalManifest(evalManifest(st, m1), m2)
    case EdgeList(lst) => evalEdges(st, lst)._1
    case Define(f, args, m) => {
      if (st.definedTypes.contains(f)) {
        throw EvalError(s"$f is already defined as a type")
      }
      else {
        // TODO(arjun): ensure no duplicate identifiers
        val vt = VDefinedType(f, st.env, args.map(a => a.id -> a.default).toMap,
                              m)
        st.copy(definedTypes = st.definedTypes + (f -> vt))
      }
    }
    case Class(f, args, _, m) => {
      if (st.classes.contains(f)) {
        throw EvalError(s"$f is already defined as a class")
      }
      else {
        // TODO(arjun): ensure no duplicate identifiers
        val vc = VClass(f, st.env,
                        args.map(a => a.id -> a.default).toMap,
                        m)
        st.copy(classes = st.classes + (f -> vc))
      }
    }
    case ESet(x, e) => st.copy(env = st.env + (x -> evalExpr(st.env, e)))
    case ITE(e, m1, m2) => evalBool(st.env, e) match {
      case true => evalManifest(st, m1)
      case false => evalManifest(st, m2)
    }
    case Include(titleExpr) => {
      val title = evalTitle(st.env, titleExpr)
      // TODO(arjun): Dependencies? Does include make the outer class depend on this class?
      val res = ResourceVal("class", title, Map())
      val node = Node("class", title)
      st.copy(resources = st.resources + (node -> res), deps = st.deps + node)
    }
    case MApp("fail", Seq(str)) => evalExpr(st.env, str) match {
      // TODO(arjun): Different type of error
      case Str(str) => throw EvalError(s"user-defined failure: $str")
      case v => throw EvalError(s"expected string argument, got $v ${manifest.pos}")
    }
    case MApp(f, _) => throw NotImplemented("unsupported function: $f ${manifest.pos}")
    case MCase(e, cases) => {
      val v = evalExpr(st.env, e)
      cases.find(c => matchCase(st.env, v, c)) match {
        case None => st
        case Some(CaseDefault(m)) => evalManifest(st, m)
        case Some(CaseExpr(_, m)) => evalManifest(st, m)
      }
    }
  }

  // Helps implement conditionals. "Truthy" values are mapped to Scala's true
  // and "falsy" values are mapped to Scala's false.
  def evalBool(env: Env, expr: Expr): Boolean = evalExpr(env, expr) match {
    case Bool(true) => true
    case Bool(false) => false
    case Undef => false
    case v => throw EvalError(s"predicate evaluated to $v")
  }

  def evalExpr(env: Env, expr: Expr): Expr = expr match {
    case Undef => Undef
    case Num(n) => Num(n)
    case Str(str) => StringInterpolator.interpolate(env, str)
    case Bool(b) => Bool(b)
    case EResourceRef(typ, title) => EResourceRef(typ.toLowerCase, evalExpr(env, title))
    case Eq(e1, e2) => Bool(evalExpr(env, e1) == evalExpr(env, e2))
    case Not(e) => Bool(!evalBool(env, e))
    case Var(x) => env.getOrError(x)
    case Array(es) => Array(es.map(e => evalExpr(env, e)))
    case App("template", Seq(e)) => evalExpr(env, e) match {
      // TODO(arjun): This is bogus. It is supposed to use filename as a
      // template string. The file contents have patterns that refer to variables
      // in the environment.
      case Str(filename) => Str(filename)
      case _ => throw EvalError("template function expects a string argument")
    }
    case App(f, args) => throw NotImplemented(s"function $f (${expr.pos})")
    case Cond(e1, e2, e3) => evalBool(env, e1) match {
      case true => evalExpr(env, e2)
      case false => evalExpr(env, e3)
    }
    case _ => throw NotImplemented(expr.toString)
  }

  def splice[A](outer: Graph[A, DiEdge], node: A,
                inner: Graph[A, DiEdge]): Graph[A, DiEdge] = {
    val innerNode = outer.get(node)
    val toEdges = (for (from <- innerNode.diPredecessors;
                        to <- inner.nodes.filter(_.inDegree == 0))
                   yield (DiEdge(from.value, to.value)))
    val fromEdges = (for (from <- inner.nodes.filter(_.outDegree == 0);
                          to <- innerNode.diSuccessors)
                     yield (DiEdge(from.value, to.value)))
    outer ++ inner ++ fromEdges ++ toEdges - innerNode
  }

  def mergeNodes[A](g: Graph[A, DiEdge], src1: A, src2: A, dst: A): Graph[A, DiEdge] = {
    val es1 = for (x <- g.get(src1).diPredecessors) yield (DiEdge(x.value, dst))
    val es2 = for (x <- g.get(src2).diPredecessors) yield (DiEdge(x.value, dst))
    val es3 = for (x <- g.get(src1).diSuccessors) yield (DiEdge(dst, x.value))
    val es4 = for (x <- g.get(src2).diSuccessors) yield (DiEdge(dst, x.value))
    g ++ es1 ++ es2 ++ es3 ++ es4 + dst - src1 - src2
  }

  def evalArgs(formals: Map[String, Option[Expr]], actuals: Map[String, Expr], env: Env): Map[String, Expr] = {
    val unexpected = actuals.keySet -- actuals.keySet
    if (unexpected.isEmpty == false) {
      throw EvalError(s"unexpected arguments: ${unexpected}")
    }
    formals.toList.map {
      case (x, None) => actuals.get(x) match {
        case Some(v) => (x, v)
        case None => throw EvalError(s"expected argument $x")
      }
      case (x, Some(default)) => actuals.get(x) match {
        case Some(v) => (x, v)
        // Notice that default expressions are evaluated in the lexical scope
        // of the type definition, but with $title bound dynamically.
        case None => (x, evalExpr(env, default))
      }
    }.toMap
  }

  def instantiateClass(st: State, node: Node): State = {
    require(node.typ == "class")
    val title = node.title
    st.classGraph.get(title) match {
      // Class has already been instantiated, splice it in where necesarry
      case Some(deps) =>
          st.copy(deps = splice(st.deps, st.deps.get(Node("class", title)), deps))
      case None => st.classes.get(title) match {
        case Some(VClass(_, env, formals, m)) => {
          val ResourceVal(_, _, actuals) = st.resources(node)
          val env1 = env.forceSet("title", Str(node.title)).forceSet("name", Str(node.title))
          val evaluated = evalArgs(formals, actuals, env1)
          val env2 = (env.newScope ++ evaluated).default("title", Str(node.title)).default("name", Str(node.title))
          val st1 = evalManifest(st.copy(deps = Graph.empty, env = env2), m)
          st1.copy(
            deps = splice(st.deps, st.deps.get(Node("class", title)), st1.deps),
            classGraph = st1.classGraph + (title -> st1.deps)
          )
        }
        case None => throw EvalError(s"class not found: $title ${st.classes.keys}")
      }
    }
  }

  def instantiateType(st: State, node: Node): State = {
    val VDefinedType(_, env, formals, m) = st.definedTypes(node.typ)
    val ResourceVal(_, _, actuals) = st.resources(node)
    val unexpected = actuals.keySet -- actuals.keySet
    if (unexpected.isEmpty == false) {
      throw EvalError(s"unexpected arguments: ${unexpected}")
    }
    val env1 = env.forceSet("title", Str(node.title)).forceSet("name", Str(node.title))
    val evaluated = evalArgs(formals, actuals, env1)
    val env2 = (env.newScope ++ evaluated).default("title", Str(node.title)).default("name", Str(node.title))
    val st1 = evalManifest(st.copy(deps = Graph.empty, env = env2), m)
    st1.copy(deps = splice(st.deps, node, st1.deps), resources = st1.resources - node)
  }


  def evalLoop(st: State): State = {
    val st1 = st
    // Select newly instantiated classes and splice them into the graph
    val instClasses = st1.deps.nodes.filter(_.typ == "class").map(_.value)
    val st2 = instClasses.foldLeft(st1)(instantiateClass)
    // Select newly instantiated defined types and splice them into the graph
    val newInstances = st2.deps.nodes
      .filter(node => st2.definedTypes.contains(node.typ))
      .map(_.value)
    val st3 = newInstances.foldLeft(st2)(instantiateType)
    if (newInstances.isEmpty && instClasses.isEmpty) {
      st3 // st1 == st2 == st3
    }
    else {
      evalLoop(st3)
    }
  }

  val emptyState = State(
    resources = Map(),
    deps = Graph.empty,
    env = Env.empty + ("title" -> Str("main")) + ("name" -> Str("main")),
    definedTypes = Map(),
    classes = Map(),
    classGraph = Map(),
    stages = Map(),
    aliases = Map())

  def updateStage(res: (Node, ResourceVal), stages: Map[String, Set[Node]]): Map[String, Set[Node]] = res match {
    case (node, ResourceVal(_, _, attrMap)) =>
      attrMap.get("stage") match {
        case Some(Str(stage)) => addStage(stage, node, stages)
        case Some(stage) => throw EvalError(s"Stage evaluated to non-string. $stage")
        case None => addStage("main", node, stages)
      }
  }

  def addStage(stage: String, node: Node, stages: Map[String, Set[Node]]): Map[String, Set[Node]] =
    stages.get(stage) match {
      case None => stages + (stage -> Set(node))
      case Some(set) => stages + (stage -> (set + node))
    }

  def expandStage(stage: Node, st: State): State = {
    require(stage.typ == "stage")
    val stageNodes = st.stages.getOrElse(stage.title, throw new Exception(s"Stage should be in map. $stage")).filter(x => st.deps.contains(x))
    val dependencies = st.deps.get(stage).incoming.map(x => st.stages.getOrElse(x.head.value.title, throw new Exception(s"Stage should be in map. $stage"))).flatten.filter(x => st.deps.contains(x))
    val st2 =
      stageNodes.foldRight(st)((n, st1) =>
        st1.copy(
          deps = st1.deps ++ dependencies.map(x => DiEdge(x, n))
        )
      )
    st2.copy( deps = st2.deps - stage )
  }

  def stageExpansion(st: State): State = {
    val instStages = st.deps.nodes.filter(_.typ == "stage").map(_.value)
    instStages.foldRight(st)(expandStage)
  }

  def eliminateAliases(st: State): State = {
    val deps = st.aliases.toSeq.foldRight(st.deps) { case ((alias, target), g) => mergeNodes(g, alias, target, target) }
    st.copy(deps = deps, aliases = Map())
  }

  def eval(manifest: Manifest): EvaluatedManifest = {
    val st = eliminateAliases(stageExpansion(evalLoop(evalManifest(emptyState, manifest))))
    EvaluatedManifest(st.resources, st.deps)
  }
}
