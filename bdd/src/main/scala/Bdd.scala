package bdd

import scala.collection.mutable.Map

trait Bdd[X] {

  type Node

  val bddTrue: Node
  val bddFalse: Node
  def bddVar(x: X): Node
  def bddApply(op: (Boolean, Boolean) => Boolean, lhs: Node, rhs: Node): Node
  def bddRestrict(node: Node, variable: X, value: Boolean): Node
  def bddRestrictAll(node: Node, pairs: Seq[(X, Boolean)]): Node = pairs.foldRight(node){
    case ((x, b), acc) => bddRestrict(acc, x, b)
  }
  def bddFold[B](trueAcc: B, falseAcc: B)(node: Node, op: (B, X, B) => B): B

  object Implicits {

    implicit class RichNode(node: Node) {

      def &&(other: Node): Node = bddApply((x, y) => x && y, node, other)
      def ||(other: Node): Node = bddApply((x, y) => x || y, node, other)
      def unary_!(): Node = bddApply((x, y) => !(x && y), node, bddTrue)

    }

  }

  def cacheSize: Int

}

object Bdd {

  def apply[X](varLT: (X, X) => Boolean): Bdd[X] = new BddImpl(varLT)

}

private class BddImpl[X](varLT : (X, X) => Boolean) extends Bdd[X] {

  type Node = Int

  sealed trait Bdd
  case class Branch(x: X, lo: Node, hi: Node) extends Bdd
  case class Leaf(b: Boolean) extends Bdd

  var nextIndex = 0
  val t: Map[Node, Bdd] = Map()
  val h: Map[Bdd, Node] = Map()
  val bddTrue = bddToNode(Leaf(true))
  val bddFalse = bddToNode(Leaf(false))

  def cacheSize = t.size

  def bddToNode(bdd: Bdd): Node = {
    h.get(bdd) match {
      case Some(n) => n
      case None => {
        val n = nextIndex
        nextIndex = nextIndex + 1
        h += (bdd -> n)
        t += (n -> bdd)
        n
      }
    }
  }

  def nodeToBdd(n: Node): Bdd = t(n)

  def mk(i: X, lo: Node, hi: Node): Node = {
    if (lo == hi) {
      lo
    }
    else {
      bddToNode(Branch(i, lo, hi))
    }
  }

  def bddVar(x: X): Node = bddToNode(Branch(x, bddFalse, bddTrue))

  def bddApply(op: (Boolean, Boolean) => Boolean, lhs: Node, rhs: Node): Node = {
    (nodeToBdd(lhs), nodeToBdd(rhs)) match {
      case (Leaf(b1), Leaf(b2)) => bddToNode(Leaf(op(b1, b2)))
      case (Leaf(_), Branch(x, lo, hi)) => {
        mk(x, bddApply(op, lhs, lo), bddApply(op, lhs, hi))
      }
      case (Branch(x, lo, hi), Leaf(_))  => {
        mk(x, bddApply(op, lo, rhs), bddApply(op, hi, rhs))
      }
      case (Branch(x1, lo1, hi1), Branch(x2, lo2, hi2)) => {
        if (x1 == x2) {
          mk(x1, bddApply(op, lo1, lo2), bddApply(op, hi1, hi2))
        }
        else if (varLT(x1, x2)) {
          mk(x1, bddApply(op, lo1, rhs), bddApply(op, hi1, rhs))
        }
        else {
          mk(x2, bddApply(op, lhs, lo2), bddApply(op, lhs, hi2))
        }
      }
    }
  }

  def bddRestrict(node: Node, variable: X, value: Boolean): Node = {
    def res(n: Node): Node = nodeToBdd(n) match {
      case Branch(x, lo, hi) => if (varLT(variable, x)) {
        n
      } else if (varLT(x, variable)) {
        mk(x, res(lo), res(hi))
      } else if (value) {
        hi
      } else {
        lo
      }
      case _ => n
    }
    res(node)
  }

  def bddFold[B](trueAcc: B, falseAcc: B)(node: Node, op: (B, X, B) => B): B = nodeToBdd(node) match {
    case Branch(x, lo, hi) => {
      val fold = bddFold(trueAcc, falseAcc)_
      op(fold(lo, op), x, fold(hi, op))
    }
    case Leaf(true) => trueAcc
    case Leaf(false) => falseAcc
  }

}