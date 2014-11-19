package equiv.desugar

import equiv.ast
import equiv.ast._
import java.nio.file.{Path, Paths}

sealed trait FSKATExpr
case object Id extends FSKATExpr
case object Err extends FSKATExpr
case class MkDir(path: Path) extends FSKATExpr
case class RmDir(path: Path) extends FSKATExpr
case class Create(path: Path) extends FSKATExpr
case class Delete(path: Path) extends FSKATExpr
case class Link(path: Path) extends FSKATExpr
case class Unlink(path: Path) extends FSKATExpr
case class Shell(path: Path) extends FSKATExpr // TODO
case class Filter(pred: ast.Predicate) extends FSKATExpr
case class Seqn(e1: FSKATExpr, e2: FSKATExpr) extends FSKATExpr
case class Opt(lhs: FSKATExpr, rhs: FSKATExpr) extends FSKATExpr

object MkDir {

  def apply(path: String): MkDir = MkDir(Paths.get(path))

}
object FSKATExpr {

  def gatherPaths(e: FSKATExpr): Set[Path] = e match {
    case Id | Err => Set()
    case MkDir(p) => Set(p)
    case RmDir(p) => Set(p)
    case Create(p) => Set(p)
    case Link(p)  => Set(p)
    case Unlink(p) => Set(p)
    case Delete(p) => Set(p)
    case Shell(p) => Set(p) // TODO(arjun): fishy
    case Filter(pr) => equiv.ast.Predicate.gatherPaths(pr)
    case Seqn(e1, e2) => gatherPaths(e1) union gatherPaths(e2)
    case Opt(lhs, rhs) => gatherPaths(lhs) union gatherPaths(rhs)
   }

}


object Desugar {

  def apply (expr: Expr): FSKATExpr = expr match {
    case Block(exprs @ _*) => exprs.foldRight(Id : FSKATExpr)((lhs, rhs) => Seqn(Desugar(lhs), rhs))
    case ast.Filter(a) => Filter(a)
    case If(cond, trueBranch, falseBranch) => Opt(Seqn(Filter(cond), Desugar(trueBranch)),
                                                  Seqn(Filter(ast.Not(cond)),  Desugar(falseBranch)))
    case CreateFile(p, _) => Create(p)
    case DeleteFile(p) => Delete(p)
    case ast.MkDir(p) => MkDir(p)
    case ast.RmDir(p) => RmDir(p)
    case ast.Link(p, _) => Link(p)
    case ast.Unlink(p) => Unlink(p)
    case ShellExec(cmd) => Shell(Paths.get("/tmp/script.sh")) // Assume that command is written to file /tmp/script.sh which shell executes
  }
}

object PrettyPrintFSKATExpr {

  private def printPath(p: Path): String = p.toAbsolutePath.toString

  def apply(expr: FSKATExpr): String = expr match {
    case Id => "id"
    case Err => "err"
    case MkDir(p) => s"mkdir(${printPath(p)})"
    case RmDir(p) => s"rmdir(${printPath(p)})"
    case Create(p) => s"create(${printPath(p)})"
    case Delete(p) => s"delete(${printPath(p)})"
    case Link(p) => s"link(${printPath(p)})"
    case Unlink(p) => s"unlink(${printPath(p)})"
    case Shell(p) => s"shell(${printPath(p)})"
    case Filter(pr) => s"(filter(${ast.PrettyPrint.printPred(pr)}))"
    case Seqn(x, y) => s"(${PrettyPrintFSKATExpr(x)} o ${PrettyPrintFSKATExpr(y)})"
    case Opt(x, y) => s"(${PrettyPrintFSKATExpr(x)} + ${PrettyPrintFSKATExpr(y)})"
  }
}
