package equiv.sat

import equiv._
import equiv.desugar._
import equiv.ast

import z3.scala._
import z3.scala.dsl._
import z3.scala.dsl.Operands._

import java.nio.file.{Paths, Path}

import scala.collection.immutable.HashMap
import scala.annotation.tailrec

class Z3Puppet {

  import equiv.desugar.Desugar._

  implicit val context = new Z3Context(new Z3Config("MODEL" -> true, "TIMEOUT" -> 3000))
  private val z3 = context

  // z3 sorts in our model
  val pathSort = z3.mkUninterpretedSort("Path")
  val fsSort   = z3.mkUninterpretedSort("FS")
  val boolSort = z3.mkBoolSort

  val initfs = z3.mkConst("FSINIT", fsSort)
  val fserr  = z3.mkConst("FSERR", fsSort)

  val root = z3.mkConst("/", pathSort)

  val pexists = z3.mkFuncDecl("pexists", Seq(pathSort, fsSort), boolSort)
  val isdir   = z3.mkFuncDecl("isdir",   Seq(pathSort, fsSort), boolSort)
  val isfile  = z3.mkFuncDecl("isfile",  Seq(pathSort, fsSort), boolSort)
  val islink  = z3.mkFuncDecl("islink",  Seq(pathSort, fsSort), boolSort)

  private def toZ3AST(p: Path): Z3AST = z3.mkConst(p.toString, pathSort)

  private object model extends ast.PredModel {

    type S = Z3AST
    type B = Z3AST // The type of boolean expressions in the model
    type P = Z3AST

    val trueB = true.ast(context)
    def falseB = false.ast(context)
    def andB(e1: B, e2: B): B = (e1 && e2).ast(context)
    def orB(e1: B, e2: B): B = (e1 || e2).ast(context)
    def notB(e: B): B = (!e).ast(context)
    def ifB(e1: B, e2: B, e3: B) = ((e1 --> e2) && (!e1 --> e3)).ast(context)
    def iffB(e1: B, e2: B) = (e1 <--> e2).ast(context)
    def eqState(s1: S, s2: S): B = (s1 === s2)
    def errState: S = fserr
    def mkState(): S = z3.mkFreshConst("fs", fsSort)

    def exists(path: P, state: S): B = pexists(path, state)
    def isDir(path: P, state: S): B = isdir(path, state)
    def isFile(path: P, state: S): B = isfile(path, state)
    def isLink(path: P, state: S): B = islink(path, state)

  }

  private def predeval(pr: ast.Predicate, infs: Z3AST, pathmap: Map[Path, Z3AST]) = model.eval(pr, infs, pathmap)

  // private def predeval(pr: ast.Predicate, infs: Z3AST)
  //                     (implicit pathmap: Map[Path, Z3AST]): Z3AST = pr match {
  //   case ast.True => (true).ast(context)
  //   case ast.False => (false).ast(context)
  //   case ast.Exists(p) => pexists(pathmap(p), infs)
  //   case ast.IsDir(p) => isdir(pathmap(p), infs)
  //   case ast.IsRegularFile(p) => isfile(pathmap(p), infs)
  //   case ast.IsLink(p) => islink(pathmap(p), infs)
  //   case ast.And(lhs, rhs) => (predeval(lhs, infs) && predeval(rhs, infs)).ast(context)
  //   case ast.Or(lhs, rhs) => (predeval(lhs, infs) || predeval(rhs, infs)).ast(context)
  //   case ast.Not(oper) => (!predeval(oper, infs)).ast(context)
  // }

  private def filter(pr: ast.Predicate, infs: Z3AST, outfs: Z3AST)
                    (implicit pathmap: Map[Path, Z3AST]): Z3AST = {
    ((predeval(pr, infs, pathmap) --> idfs(infs, outfs, pathmap)) &&
     (!predeval(pr, infs, pathmap) --> errfs(outfs, pathmap))).ast(context)
  }

  private def mkdir(infs: Z3AST, outfs: Z3AST, path: Path, pathmap: Map[Path, Z3AST]): Z3AST = {
    val z3parentpath = pathmap(path.getParent)
    val z3path = pathmap(path)
    val e = (pexists(z3parentpath, infs) --> (pexists(z3path, outfs) && idfs(infs, outfs, pathmap - path))) &&
            (!pexists(z3parentpath, infs) --> errfs(outfs, pathmap))
    e.ast(context)
  }

  private def idfs(infs: Z3AST, outfs: Z3AST, pathmap: Map[Path, Z3AST]): Z3AST = {
    z3.mkAnd((pathmap.toSeq map {case (_, p) => pexists(p, infs) === pexists(p, outfs)}):_*)
  }

  private def errfs(fs: Z3AST, pathmap: Map[Path, Z3AST]): Z3AST = {
    z3.mkAnd((pathmap.toSeq map {case (_, p) => (pexists(p, fserr) === (pexists(p, fs)))}):_*)
  }

  private def eval(e: FSKATExpr,
                   initfs: Z3AST)
                   (implicit pathmap: Map[Path, Z3AST]): Z3AST /* Final FS */ = e match {

    case Id => {
      val outfs = z3.mkFreshConst("fs", fsSort)
      solver.assertCnstr(idfs(initfs, outfs, pathmap))
      outfs
    }

    case Err => {
      val outfs = z3.mkFreshConst("fs", fsSort)
      solver.assertCnstr(errfs(outfs, pathmap))
      outfs
    }

    case MkDir(p) => {
      val outfs = z3.mkFreshConst("fs", fsSort)
      solver.assertCnstr(mkdir(initfs, outfs, p, pathmap))
      outfs
    }

    case RmDir(p) => {
      val outfs = z3.mkFreshConst("fs", fsSort)
      // solver.assertCnstr(rmdir(initfs, outfs, p))
      solver.assertCnstr(mkdir(initfs, outfs, p, pathmap)) // TODO
      outfs
    }

    case Create(p) => {
      val outfs = z3.mkFreshConst("fs", fsSort)
      // solver.assertCnstr(create(initfs, outfs, p))
      solver.assertCnstr(mkdir(initfs, outfs, p, pathmap)) // TODO
      outfs
    }

    case Delete(p) => {
      val outfs = z3.mkFreshConst("fs", fsSort)
      // solver.assertCnstr(delete(initfs, outfs, p))
      solver.assertCnstr(mkdir(initfs, outfs, p, pathmap)) // TODO
      outfs
    }

    case Link(p) => {
      val outfs = z3.mkFreshConst("fs", fsSort)
      // solver.assertCnstr(link(initfs, outfs, p))
      solver.assertCnstr(mkdir(initfs, outfs, p, pathmap)) // TODO
      outfs
    }

    case Unlink(p) => {
      val outfs = z3.mkFreshConst("fs", fsSort)
      // solver.assertCnstr(unlink(initfs, outfs, p))
      solver.assertCnstr(mkdir(initfs, outfs, p, pathmap)) // TODO
      outfs
    }

    case Shell(p) => {
      val outfs = z3.mkFreshConst("fs", fsSort)
      // solver.assertCnstr(shell(initfs, outfs, p))
      solver.assertCnstr(mkdir(initfs, outfs, p, pathmap)) // TODO
      outfs
    }

    case Filter(pr) => {
      val outfs = z3.mkFreshConst("fs", fsSort)
      solver.assertCnstr(filter(pr, initfs, outfs))
      outfs
    }

    case Seqn(e1, e2) => eval(e2, eval(e1, initfs))

    case Opt(lhs, rhs) => {
      val outfs1 = eval(lhs, initfs)
      val outfs2 = eval(rhs, initfs)
      outfs2 // random TODO
    }
  }

  private def parentshouldexist(fs: Z3AST, pathmap: Map[Path, Z3AST]): Z3AST = {

    import collection.mutable.{HashMap => mutHashMap}
    import collection.mutable.{Set => mutSet}

    // Convert to parent child map
    val flat_fstree = mutHashMap.empty[Path, mutSet[Path]]
    pathmap.keySet.foreach ((p) => {
        val parent = p.getParent
        if (null != parent) {
          val s = flat_fstree.get(parent) getOrElse mutSet.empty
          flat_fstree.put(parent, (s + p))
        }
      })

    z3.mkAnd((flat_fstree.map({ case (p, ch) => {
      z3.mkImplies(z3.mkNot(pexists(pathmap(p),fs)),
                   z3.mkAnd((ch.toSeq map {p => z3.mkNot(pexists(pathmap(p), fs))}):_*))
    }}).toSeq):_*)
  }

  def isEquiv(e1: equiv.ast.Expr, e2: equiv.ast.Expr): Option[Boolean] = {

    solver.push

    val e1fskat = Desugar(e1)
    val e2fskat = Desugar(e2)

    val allpaths = ancestors(FSKATExpr.gatherPaths(e1fskat) union FSKATExpr.gatherPaths(e2fskat))

    val pathmap = HashMap(((allpaths map {p=>(p->toZ3AST(p))}).toSeq):_*) + (Paths.get("/")->root)

    // define errfs
    solver.assertCnstr(z3.mkAnd((pathmap.toSeq.map({case(_, p) => (!pexists(p, fserr)).ast(context)})):_*))

    // assert this condition for only initial FS and all FS derived from initial FS will follow
    solver.assertCnstr(parentshouldexist(initfs, pathmap))

    val fsfinal_e1 = eval(e1fskat, initfs)(pathmap)
    val fsfinal_e2 = eval(e2fskat, initfs)(pathmap)

    // assert that paths are distinct
    val z3paths = pathmap.values.toSeq
    solver.assertCnstr(z3.mkDistinct(z3paths: _*))

    // initfs is not the same as errfs, atleast root exists on initFS
    solver.assertCnstr(pexists(root, initfs))

    if(Some(true) != sanityCheck()) {
      printAssertions()
      solver.pop()
      println("Sanity check failed")
      return Some(false)
    }

    // Assert that final fs_s are same wrt to all the paths
    solver.assertCnstr(z3.mkNot(z3.mkAnd((pathmap.toSeq map {case(_, p) => (pexists(p, fsfinal_e1) === pexists(p, fsfinal_e2))}):_*)))

    // printAssertions()

    val result = solver.checkAssumptions() map { b => !b }

    solver.pop()

    result
  }

  val solver = z3.mkSolver

  def sanityCheck(): Option[Boolean] = {
    solver.checkAssumptions()
  }

  def printAssertions() {
    println("============================== ASSERTIONS =====================================")
    solver.getAssertions().toSeq.foreach(println)
    println("-------------------------------------------------------------------------------")
  }
}
